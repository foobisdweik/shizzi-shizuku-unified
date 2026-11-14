package datapath

import (
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	// maxInFlightTCP bounds half-open connections the forwarder will track.
	// A connection stays in flight until its upstream dial finishes, which can
	// take up to dialTimeout. A client that opens many connections to dead
	// hosts at once, such as a torrent client, can fill this. While it is full,
	// new SYNs are dropped and every other connection waits for a retry. The
	// cap exists to bound memory under a SYN flood, not to shape traffic.
	maxInFlightTCP = 2048

	// rcvWnd of 0 lets netstack pick its default receive window.
	defaultRcvWnd = 0

	// dialTimeout bounds how long a client waits for an unreachable host.
	dialTimeout = 10 * time.Second

	// udpFlowTimeout closes a UDP association after this long with no
	// datagram in either direction. DNS exchanges finish in milliseconds;
	// QUIC keeps its own keepalives well inside this.
	udpFlowTimeout = 60 * time.Second

	// relayBufferSize is the copy buffer for each direction of a TCP flow.
	// io.Copy would use 32 KiB. A larger buffer moves more bytes per read and
	// write, so a fast transfer makes fewer system calls and stack entries.
	relayBufferSize = 64 << 10

	// udpSocketBuffer is the receive and send buffer requested for both sides
	// of a UDP flow. Android's and gVisor's defaults are about 208 KiB, which
	// a fast QUIC burst can overflow, and an overflow drops datagrams. The
	// kernel silently caps the request at its own maximum.
	udpSocketBuffer = 2 << 20

	// maxConsecutiveUDPErrors ends a flow whose socket keeps failing. Single
	// errors are expected and survivable on UDP: a full send buffer, or an
	// ICMP port unreachable reported on the next read.
	maxConsecutiveUDPErrors = 16
)

// installForwarders routes inbound flows to userspace handlers.
//
// Without these the stack silently drops every packet: nothing is listening on
// the addresses tethered clients dial, because those addresses belong to hosts
// out on the internet rather than to this stack.
func installForwarders(netStack *stack.Stack, dialer *net.Dialer) {
	tcpForwarder := tcp.NewForwarder(netStack, defaultRcvWnd, maxInFlightTCP,
		func(request *tcp.ForwarderRequest) { forwardTCP(request, dialer) })
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(netStack,
		func(request *udp.ForwarderRequest) bool { return forwardUDP(request, dialer) })
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

// forwardTCP proxies one client connection to its intended destination.
//
// The upstream socket is dialed before the client endpoint is created, so a
// refused or unreachable destination sends the client a RST rather than
// completing a handshake that then dies.
func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer) {
	id := request.ID()

	upstream, err := dialer.Dial("tcp", destinationOf(id))
	if err != nil {
		request.Complete(true)
		return
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		request.Complete(true)
		return
	}
	request.Complete(false)

	client := gonet.NewTCPConn(&queue, endpoint)
	go relay(client, upstream)
}

// forwardUDP proxies one client datagram flow to its destination.
//
// Returning false leaves the request unhandled, which makes the stack send the
// client an ICMP port unreachable — the right answer when the destination
// could not be reached, and better than dropping the datagram silently.
func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer) bool {
	id := request.ID()

	upstream, err := dialer.Dial("udp", destinationOf(id))
	if err != nil {
		return false
	}
	enlargeSocketBuffers(upstream)

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		return false
	}
	// notify=true applies the stack's own bounds, as setsockopt would.
	endpoint.SocketOptions().SetReceiveBufferSize(udpSocketBuffer, true)
	endpoint.SocketOptions().SetSendBufferSize(udpSocketBuffer, true)

	client := gonet.NewUDPConn(&queue, endpoint)
	go relayDatagrams(client, upstream, udpFlowTimeout)
	return true
}

// enlargeSocketBuffers asks the kernel for larger UDP buffers on upstream.
//
// Errors are ignored: a refused request leaves the default buffers, which is
// how every flow ran before this existed.
func enlargeSocketBuffers(upstream net.Conn) {
	conn, ok := upstream.(*net.UDPConn)
	if !ok {
		return
	}
	_ = conn.SetReadBuffer(udpSocketBuffer)
	_ = conn.SetWriteBuffer(udpSocketBuffer)
}

// relay copies bytes both ways until either side finishes, then closes both.
//
// Half-close is deliberately not propagated: a client that shuts down its write
// side still expects to read the response, but tracking that per direction adds
// state this prototype does not need. Both sides close when either completes.
func relay(client, upstream net.Conn) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyStream(upstream, client)
		done <- struct{}{}
	}()
	go func() {
		copyStream(client, upstream)
		done <- struct{}{}
	}()

	<-done
}

// relayBuffers recycles TCP copy buffers between flows, so a burst of short
// connections does not allocate and collect a buffer for each one.
var relayBuffers = sync.Pool{
	New: func() any {
		buffer := make([]byte, relayBufferSize)
		return &buffer
	},
}

// copyStream copies src to dst through a pooled relayBufferSize buffer.
//
// The wrappers hide ReadFrom and WriteTo. Without them io.CopyBuffer would
// hand the copy to *net.TCPConn, which ignores the buffer passed in and falls
// back to its own 32 KiB one, because neither splice nor sendfile can read a
// gVisor endpoint.
func copyStream(dst io.Writer, src io.Reader) {
	buffer := relayBuffers.Get().(*[]byte)
	defer relayBuffers.Put(buffer)

	_, _ = io.CopyBuffer(writerOnly{dst}, readerOnly{src}, *buffer)
}

type writerOnly struct{ io.Writer }

type readerOnly struct{ io.Reader }

// relayDatagrams copies datagrams both ways until the flow goes idle.
//
// Unlike TCP there is no close to observe, so an idle deadline is the only
// thing that reclaims the association. The deadline is shared: traffic in
// either direction keeps the whole flow alive. Before, each direction timed
// out alone, so a one-way stream, where the client never replies, was torn
// down after the timeout even while data was still arriving.
func relayDatagrams(client, upstream net.Conn, idleTimeout time.Duration) {
	defer client.Close()
	defer upstream.Close()

	clock := newFlowClock(idleTimeout)
	done := make(chan struct{}, 2)

	go func() {
		copyDatagrams(upstream, client, clock)
		done <- struct{}{}
	}()
	go func() {
		copyDatagrams(client, upstream, clock)
		done <- struct{}{}
	}()

	<-done
}

// flowClock records when a UDP flow last carried a datagram in either
// direction. Atomic because each direction's goroutine reads and writes it.
type flowClock struct {
	lastActive  atomic.Int64
	idleTimeout time.Duration
}

func newFlowClock(idleTimeout time.Duration) *flowClock {
	clock := &flowClock{idleTimeout: idleTimeout}
	clock.touch()
	return clock
}

func (c *flowClock) touch() {
	c.lastActive.Store(time.Now().UnixNano())
}

// deadline is when the flow expires if neither direction carries a datagram.
func (c *flowClock) deadline() time.Time {
	return time.Unix(0, c.lastActive.Load()).Add(c.idleTimeout)
}

// copyDatagrams copies datagrams from src to dst until the flow expires, a
// socket closes, or a socket keeps failing.
//
// A failed write drops one datagram and the copy continues. UDP promises no
// delivery, so QUIC and other protocols recover from a lost datagram. They do
// not recover cheaply from a closed flow, which forces a new upstream port.
func copyDatagrams(dst, src net.Conn, clock *flowClock) {
	buffer := make([]byte, maxDatagramSize)
	consecutiveErrors := 0

	for {
		if err := src.SetReadDeadline(clock.deadline()); err != nil {
			return
		}

		read, err := src.Read(buffer)
		if read > 0 {
			clock.touch()
			if _, writeErr := dst.Write(buffer[:read]); writeErr != nil {
				if isClosed(writeErr) {
					return
				}
				consecutiveErrors++
			} else {
				consecutiveErrors = 0
			}
		}

		if err != nil {
			if isTimeout(err) {
				// The other direction may have carried traffic while this
				// read waited. If so, the shared deadline has moved on.
				if time.Now().Before(clock.deadline()) {
					continue
				}
				return
			}
			if isClosed(err) || errors.Is(err, io.EOF) {
				return
			}
			consecutiveErrors++
		}

		if consecutiveErrors >= maxConsecutiveUDPErrors {
			return
		}
	}
}

func isTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

func isClosed(err error) bool {
	return errors.Is(err, net.ErrClosed)
}

// maxDatagramSize is large enough for any UDP payload a client can send
// through a 1500-byte-MTU link without fragmentation surprises.
const maxDatagramSize = 65535

// destinationOf renders the address a flow was actually addressed to.
//
// LocalAddress is the destination from the stack's perspective: the client
// dialed it, and this stack received it only because the NIC is promiscuous.
func destinationOf(id stack.TransportEndpointID) string {
	return net.JoinHostPort(
		addressString(id.LocalAddress),
		strconv.Itoa(int(id.LocalPort)),
	)
}

func addressString(address tcpip.Address) string {
	return net.IP(address.AsSlice()).String()
}
