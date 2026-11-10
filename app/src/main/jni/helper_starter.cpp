// Starts only Shizzi's helper. The Java helper verifies the installed package before publication.
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <cstdint>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static bool installed_paths(const char *apk, const char *native_dir) {
    char resolved_apk[PATH_MAX];
    char resolved_native[PATH_MAX];
    char executable[PATH_MAX];
    char expected[PATH_MAX];
    if (!realpath(apk, resolved_apk) || !realpath(native_dir, resolved_native)) return false;
    if (strncmp(resolved_apk, "/data/app/", 10) != 0) return false;
    struct stat apk_info{}, native_info{};
    if (stat(resolved_apk, &apk_info) || stat(resolved_native, &native_info)) return false;
    if (!S_ISREG(apk_info.st_mode) || !S_ISDIR(native_info.st_mode)) return false;
    char *apk_name = strrchr(resolved_apk, '/');
    if (!apk_name || strcmp(apk_name, "/base.apk") != 0) return false;
    *apk_name = '\0';
    const size_t parent_length = strlen(resolved_apk);
    if (strncmp(resolved_native, resolved_apk, parent_length) != 0 ||
        strncmp(resolved_native + parent_length, "/lib/", 5) != 0) return false;
    const ssize_t count = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (count < 0) return false;
    executable[count] = '\0';
    const int written = snprintf(expected, sizeof(expected), "%s/libshizzi_starter.so", resolved_native);
    return written > 0 && written < static_cast<int>(sizeof(expected)) && strcmp(executable, expected) == 0;
}

[[noreturn]] static void child_error(int descriptor, int error) {
    // An errno value reaches the parent only when preparation or exec fails.
    ssize_t result;
    do { result = write(descriptor, &error, sizeof(error)); } while (result < 0 && errno == EINTR);
    _exit(1);
}

static int64_t monotonic_ms() {
    struct timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

int main(int argc, char **argv) {
    if (getuid() != 2000 || geteuid() != 2000) {
        fprintf(stderr, "Start the Shizzi helper through Android debugging.\n");
        return 1;
    }
    if (argc != 3 || !installed_paths(argv[1], argv[2])) {
        fprintf(stderr, "The starter requires Shizzi's installed APK and native directory.\n");
        return 1;
    }
    int errors[2];
    if (pipe2(errors, O_CLOEXEC) != 0) {
        perror("Could not create the helper startup pipe");
        return 1;
    }
    const pid_t child = fork();
    if (child < 0) {
        perror("Could not fork the Shizzi helper");
        close(errors[0]);
        close(errors[1]);
        return 1;
    }
    if (child == 0) {
        close(errors[0]);
        if (setsid() < 0 || chdir("/") != 0) child_error(errors[1], errno);
        const int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0) child_error(errors[1], errno);
        for (int fd = STDIN_FILENO; fd <= STDERR_FILENO; ++fd) {
            if (dup2(null_fd, fd) < 0) child_error(errors[1], errno);
        }
        if (null_fd > STDERR_FILENO) close(null_fd);
        if (setenv("CLASSPATH", argv[1], 1) != 0 || setenv("SHIZZI_DETACHED", "1", 1) != 0) {
            child_error(errors[1], errno);
        }
        execl("/system/bin/app_process", "app_process", "/system/bin",
              "--nice-name=dev.shizzi:helper", "dev.shizzi.HelperMain", argv[1], argv[2], nullptr);
        child_error(errors[1], errno);
    }
    close(errors[1]);
    struct pollfd ready{errors[0], POLLIN | POLLHUP, 0};
    const int64_t deadline = monotonic_ms() + 5000;
    int result = 0;
    do {
        const int64_t now = monotonic_ms();
        if (now < 0 || now >= deadline) break;
        result = poll(&ready, 1, static_cast<int>(deadline - now));
    } while (result < 0 && errno == EINTR);
    if (result <= 0) {
        fprintf(stderr, "The helper did not execute within five seconds.\n");
        kill(child, SIGKILL);
        close(errors[0]);
        return 1;
    }
    int child_errno = 0;
    const ssize_t count = read(errors[0], &child_errno, sizeof(child_errno));
    close(errors[0]);
    if (count != 0) {
        fprintf(stderr, "Could not execute the Shizzi helper: %s\n", strerror(count > 0 ? child_errno : errno));
        return 1;
    }
    int status = 0;
    if (waitpid(child, &status, WNOHANG) == child) {
        fprintf(stderr, "The helper exited before it could connect.\n");
        return 1;
    }
    printf("Started Shizzi helper process %d. Waiting for its connection.\n", child);
    return 0;
}
