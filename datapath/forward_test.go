package datapath

import (
	"bytes"
	"crypto/rand"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

// TestTuneTCPSelectsCubic checks that the client-facing TCP leg uses CUBIC.
func TestTuneTCPSelectsCubic(t *testing.T) {
	netStack := newTestStack(t)
	tuneTCP(netStack)

	var option tcpip.CongestionControlOption
	if err := netStack.TransportProtocolOption(tcp.ProtocolNumber, &option); err != nil {
		t.Fatalf("TransportProtocolOption: %v", err)
	}
	if option != congestionControl {
		t.Fatalf("congestion control = %q, want %q", option, congestionControl)
	}
}

// TestCopyStreamPreservesBytes checks that the pooled copy delivers every byte
// in order, across several buffer lengths of data.
func TestCopyStreamPreservesBytes(t *testing.T) {
	payload := make([]byte, 5*relayBufferSize+123)
	if _, err := rand.Read(payload); err != nil {
		t.Fatal(err)
	}

	var received bytes.Buffer
	copyStream(&received, bytes.NewReader(payload))

	if !bytes.Equal(received.Bytes(), payload) {
		t.Fatalf("copied %d bytes, want %d identical bytes", received.Len(), len(payload))
	}
}

// udpPair returns two connected loopback UDP sockets: what one writes, the
// other reads.
func udpPair(t *testing.T) (*net.UDPConn, *net.UDPConn) {
	t.Helper()

	first, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	second, err := net.DialUDP("udp", nil, first.LocalAddr().(*net.UDPAddr))
	if err != nil {
		first.Close()
		t.Fatal(err)
	}
	first.Close()

	first, err = net.DialUDP("udp", first.LocalAddr().(*net.UDPAddr), second.LocalAddr().(*net.UDPAddr))
	if err != nil {
		second.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		first.Close()
		second.Close()
	})
	return first, second
}

// relayHarness wires relayDatagrams between two loopback socket pairs:
//
//	client <-> clientSide [relay] upstreamSide <-> server
type relayHarness struct {
	client, server *net.UDPConn
	finished       chan struct{}
}

func newRelayHarness(t *testing.T, idleTimeout time.Duration) *relayHarness {
	t.Helper()

	client, clientSide := udpPair(t)
	upstreamSide, server := udpPair(t)

	harness := &relayHarness{client: client, server: server, finished: make(chan struct{})}
	go func() {
		relayDatagrams(clientSide, upstreamSide, idleTimeout)
		close(harness.finished)
	}()
	return harness
}

func (h *relayHarness) isFinished() bool {
	select {
	case <-h.finished:
		return true
	default:
		return false
	}
}

func receive(t *testing.T, conn *net.UDPConn) string {
	t.Helper()

	buffer := make([]byte, 64)
	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	read, err := conn.Read(buffer)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return string(buffer[:read])
}

// TestRelayDatagramsSurvivesOneWayTraffic is the regression test for one-way
// UDP streams. The client never sends after its first datagram, but the
// server keeps sending. The flow must stay up for longer than the idle
// timeout, because traffic in either direction counts as activity.
func TestRelayDatagramsSurvivesOneWayTraffic(t *testing.T) {
	const idleTimeout = 200 * time.Millisecond
	harness := newRelayHarness(t, idleTimeout)

	if _, err := harness.client.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, harness.server); got != "hello" {
		t.Fatalf("server got %q, want %q", got, "hello")
	}

	// Four idle timeouts of server-to-client traffic only.
	stop := time.Now().Add(4 * idleTimeout)
	for time.Now().Before(stop) {
		if _, err := harness.server.Write([]byte("tick")); err != nil {
			t.Fatal(err)
		}
		if got := receive(t, harness.client); got != "tick" {
			t.Fatalf("client got %q, want %q", got, "tick")
		}
		time.Sleep(idleTimeout / 4)
	}

	if harness.isFinished() {
		t.Fatal("relay closed a flow that was still carrying traffic")
	}
}

// TestRelayDatagramsClosesIdleFlow checks that a flow with no traffic in
// either direction still ends after the idle timeout.
func TestRelayDatagramsClosesIdleFlow(t *testing.T) {
	const idleTimeout = 100 * time.Millisecond
	harness := newRelayHarness(t, idleTimeout)

	select {
	case <-harness.finished:
	case <-time.After(20 * idleTimeout):
		t.Fatal("relay did not close an idle flow")
	}
}

// scriptedConn is a net.Conn whose Read returns queued datagrams and whose
// Write fails a set number of times before it succeeds.
type scriptedConn struct {
	net.Conn

	mu         sync.Mutex
	reads      [][]byte
	failWrites int
	written    [][]byte
}

var errTransient = errors.New("no buffer space available")

func (c *scriptedConn) Read(buffer []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.reads) == 0 {
		return 0, net.ErrClosed
	}
	next := c.reads[0]
	c.reads = c.reads[1:]
	return copy(buffer, next), nil
}

func (c *scriptedConn) Write(buffer []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.failWrites > 0 {
		c.failWrites--
		return 0, errTransient
	}
	c.written = append(c.written, append([]byte(nil), buffer...))
	return len(buffer), nil
}

func (c *scriptedConn) SetReadDeadline(time.Time) error { return nil }

// TestCopyDatagramsDropsOnlyFailedDatagram checks that one failed write drops
// one datagram and does not end the flow.
func TestCopyDatagramsDropsOnlyFailedDatagram(t *testing.T) {
	src := &scriptedConn{reads: [][]byte{[]byte("lost"), []byte("kept")}}
	dst := &scriptedConn{failWrites: 1}

	copyDatagrams(dst, src, newFlowClock(time.Minute))

	if len(dst.written) != 1 || string(dst.written[0]) != "kept" {
		t.Fatalf("written = %q, want only %q", dst.written, "kept")
	}
}

// TestCopyDatagramsStopsOnRepeatedErrors checks that a destination that
// never accepts a write ends the flow instead of looping forever.
func TestCopyDatagramsStopsOnRepeatedErrors(t *testing.T) {
	reads := make([][]byte, 2*maxConsecutiveUDPErrors)
	for i := range reads {
		reads[i] = []byte("x")
	}
	src := &scriptedConn{reads: reads}
	dst := &scriptedConn{failWrites: len(reads)}

	copyDatagrams(dst, src, newFlowClock(time.Minute))

	if remaining := len(src.reads); remaining != maxConsecutiveUDPErrors {
		t.Fatalf("stopped with %d reads left, want %d", remaining, maxConsecutiveUDPErrors)
	}
}
