/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Minimal async-signal-safe crash handler.
 *
 * Native crashes (SIGSEGV/SIGBUS/SIGABRT/...) kill the process with no Java
 * exception, no crash dialog and nothing the app itself can read back — the
 * tombstone lives in the system's private data. This handler writes a bare
 * report (signal, fault address, faulting PC/LR/SP/FP, and the process memory
 * map) into the app's crash directory so the next app start can pick it up,
 * attach the GlobalLog breadcrumbs of the dying session, and surface a file
 * the user can actually hand over. After writing, the default disposition is
 * restored and the signal re-raised so the system still produces its own
 * tombstone and the death looks exactly like an unhandled crash to Android.
 *
 * Only async-signal-safe calls are used on the handler path: open/write/read/
 * close/sigaction/raise plus bionic's snprintf (documented signal-safe there).
 */

#include <jni.h>

#include <cstdarg>
#include <cinttypes>
#include <cstring>

#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <time.h>

namespace {

char g_crash_dir[256] = {0};
char g_alt_stack[64 * 1024] __attribute__((aligned(4096)));
bool g_installed = false;

void writeStr(int fd, const char* s) {
    if (fd < 0 || s == nullptr) return;
    const size_t len = strlen(s);
    const ssize_t ignored = write(fd, s, len);
    (void)ignored;
}

void writeFormatted(int fd, const char* fmt, ...) {
    if (fd < 0) return;
    char buf[256];
    va_list args;
    va_start(args, fmt);
    const int n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (n > 0) {
        const ssize_t ignored = write(fd, buf, static_cast<size_t>(n));
        (void)ignored;
    }
}

void dumpProcFile(int fd, const char* path, size_t cap) {
    const int in = open(path, O_RDONLY);
    if (in < 0) return;
    char buf[4096];
    size_t total = 0;
    ssize_t r;
    while (total < cap && (r = read(in, buf, sizeof(buf))) > 0) {
        size_t take = static_cast<size_t>(r);
        if (total + take > cap) take = cap - total;
        const ssize_t ignored = write(fd, buf, take);
        (void)ignored;
        total += take;
    }
    close(in);
}

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS: return "SIGBUS";
        case SIGABRT: return "SIGABRT";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        default: return "?";
    }
}

void writeRegisterBlock(int fd, int sig, siginfo_t* info, void* ctx) {
    writeStr(fd, "\n--- registers ---\n");
#if defined(__aarch64__)
    const ucontext_t* uc = static_cast<const ucontext_t*>(ctx);
    writeFormatted(fd, "pc=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.pc));
    writeFormatted(fd, "lr=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.regs[30]));
    writeFormatted(fd, "sp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.sp));
    writeFormatted(fd, "fp(x29)=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.regs[29]));
    for (int i = 0; i <= 28; ++i) {
        writeFormatted(fd, "x%d=%#" PRIxPTR "\n", i, static_cast<uintptr_t>(uc->uc_mcontext.regs[i]));
    }
#elif defined(__arm__)
    const ucontext_t* uc = static_cast<const ucontext_t*>(ctx);
    // bionic's arm mcontext (struct sigcontext) has flat arm_* fields, not
    // an array — the unwind chain (ip/fp/sp/lr/pc) is what matters for
    // offline symbolication.
    writeFormatted(fd, "pc=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_pc));
    writeFormatted(fd, "lr=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_lr));
    writeFormatted(fd, "sp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_sp));
    writeFormatted(fd, "fp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_fp));
    writeFormatted(fd, "ip=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_ip));
    writeFormatted(fd, "r0=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_r0));
    writeFormatted(fd, "r1=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_r1));
    writeFormatted(fd, "r2=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_r2));
    writeFormatted(fd, "r3=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_r3));
    writeFormatted(fd, "r7=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.arm_r7));
#elif defined(__x86_64__)
    const ucontext_t* uc = static_cast<const ucontext_t*>(ctx);
    writeFormatted(fd, "rip=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RIP]));
    writeFormatted(fd, "rsp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RSP]));
    writeFormatted(fd, "rbp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RBP]));
#elif defined(__i386__)
    const ucontext_t* uc = static_cast<const ucontext_t*>(ctx);
    writeFormatted(fd, "eip=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_EIP]));
    writeFormatted(fd, "esp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_ESP]));
    writeFormatted(fd, "ebp=%#" PRIxPTR "\n", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_EBP]));
#else
    (void)ctx;
#endif
    writeFormatted(fd, "signal=%d (%s) si_code=%d fault_addr=%p\n",
                   sig, signalName(sig), info ? info->si_code : 0,
                   info ? info->si_addr : nullptr);
}

void crashHandler(int sig, siginfo_t* info, void* ctx) {
    // Never recurse into ourselves.
    static volatile sig_atomic_t in_handler = 0;
    if (in_handler) {
        struct sigaction dfl;
        memset(&dfl, 0, sizeof(dfl));
        dfl.sa_handler = SIG_DFL;
        sigemptyset(&dfl.sa_mask);
        sigaction(sig, &dfl, nullptr);
        raise(sig);
        return;
    }
    in_handler = 1;

    if (g_crash_dir[0] != '\0') {
        char path[320];
        const long long now = static_cast<long long>(time(nullptr));
        snprintf(path, sizeof(path), "%s/native_crash_%lld.trace", g_crash_dir, now);
        const int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (fd >= 0) {
            writeStr(fd, "ArchiveTune native crash report\n");
            writeFormatted(fd, "time_epoch=%lld\n", now);
            writeFormatted(fd, "pid=%d tid=%ld (gettid)\n", static_cast<int>(getpid()),
                           static_cast<long>(syscall(SYS_gettid)));
            writeRegisterBlock(fd, sig, info, ctx);
            writeStr(fd, "\n--- /proc/self/maps (for offline addr2line) ---\n");
            dumpProcFile(fd, "/proc/self/maps", 512 * 1024);
            writeStr(fd, "\n--- end of report ---\n");
            close(fd);
        }
    }

    // Restore the default disposition and re-raise so the system keeps its
    // own tombstone and the process dies the way Android expects.
    struct sigaction dfl;
    memset(&dfl, 0, sizeof(dfl));
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_rukamori_archivetune_utils_CrashReporter_nativeInstall(
    JNIEnv* env, jclass, jstring dir) {
    if (dir == nullptr) return JNI_FALSE;
    const char* d = env->GetStringUTFChars(dir, nullptr);
    if (d == nullptr) return JNI_FALSE;
    const size_t len = strlen(d);
    if (len == 0 || len >= sizeof(g_crash_dir)) {
        env->ReleaseStringUTFChars(dir, d);
        return JNI_FALSE;
    }
    memcpy(g_crash_dir, d, len + 1);
    env->ReleaseStringUTFChars(dir, d);

    if (g_installed) return JNI_TRUE;
    g_installed = true;

    // Alternate stack so a stack-overflow SIGSEGV can still run the handler.
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_alt_stack;
    ss.ss_size = sizeof(g_alt_stack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    // SIGTRAP is deliberately NOT hooked: ART/debuggers use it for
    // breakpoints, and stealing it breaks attachable debugging without
    // buying anything for crash capture (matches the default set of the
    // established Android crash handlers).
    const int signals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL};
    for (int s : signals) {
        sigaction(s, &sa, nullptr);
    }
    return JNI_TRUE;
}
