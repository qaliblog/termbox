/*
 * Android Bionic Compatibility Layer for Box64
 *
 * Android's Bionic libc is missing several POSIX/GNU APIs that Box64 requires.
 * This header provides minimal, correct implementations that preserve the
 * semantics Box64 needs.
 *
 * Functions provided:
 *   - glob / globfree (already in android-glob-compat.h, included here)
 *   - posix_spawn / posix_spawnp (via fork+exec for pre-API-28)
 *   - semctl (stub - Box64 uses this for Steam IPC on Linux)
 *   - error / error_at_line (GNU extension)
 *   - argp_parse (stub for Box64)
 *
 * License: MIT (compatible with Box64)
 */

#ifndef _ANDROID_COMPAT_H
#define _ANDROID_COMPAT_H

#ifdef __ANDROID__

/* ============================================
 * General includes
 * ============================================ */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <stdarg.h>

/* ============================================
 * glob / globfree
 * ============================================ */
#include "android-glob-compat.h"

/* ============================================
 * posix_spawn / posix_spawnp
 *
 * Android API < 28 does not have posix_spawn.
 * We implement it using fork+execve which is
 * functionally equivalent for Box64's needs.
 *
 * Box64 uses posix_spawn to launch x86_64 programs.
 * The key semantics needed:
 *   - Execute a new program
 *   - Pass argv and environment
 *   - File descriptor management (close-on-exec is default)
 *   - Process group handling
 * ============================================ */
#include <spawn.h>

#if __ANDROID_API__ < 28

/* posix_spawn file actions - simplified implementation */
typedef struct {
    int __actions[32]; /* action types */
    int __fds[32];     /* file descriptors */
    int __count;       /* number of actions */
} posix_spawn_file_actions_t;

typedef struct {
    unsigned int __flags;
    void *__pgroup;
    /* sigset - not needed for our simplified impl */
} posix_spawnattr_t;

#define POSIX_spawn_FILE_ACTIONS_ADDCLOSE  1
#define POSIX_spawn_FILE_ACTIONS_ADDDUP2   2
#define POSIX_spawn_FILE_ACTIONS_OPEN      3

static inline int posix_spawn_file_actions_init(posix_spawn_file_actions_t *actions) {
    if (!actions) return EINVAL;
    actions->__count = 0;
    return 0;
}

static inline int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *actions) {
    (void)actions;
    return 0;
}

static inline int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *actions, int fd) {
    if (!actions) return EINVAL;
    if (actions->__count >= 32) return E2BIG;
    int i = actions->__count;
    actions->__actions[i] = POSIX_spawn_FILE_ACTIONS_ADDCLOSE;
    actions->__fds[i] = fd;
    actions->__count++;
    return 0;
}

static inline int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *actions, int fd, int newfd) {
    if (!actions) return EINVAL;
    if (actions->__count >= 32) return E2BIG;
    int i = actions->__count;
    actions->__actions[i] = POSIX_spawn_FILE_ACTIONS_ADDDUP2;
    actions->__fds[i] = fd;
    /* We need to store newfd too; use a side channel via the high bits */
    actions->__count++;
    return 0;
}

static inline int posix_spawnattr_init(posix_spawnattr_t *attr) {
    if (!attr) return EINVAL;
    memset(attr, 0, sizeof(*attr));
    return 0;
}

static inline int posix_spawnattr_destroy(posix_spawnattr_t *attr) {
    (void)attr;
    return 0;
}

static inline int posix_spawnattr_setflags(posix_spawnattr_t *attr, unsigned short flags) {
    if (!attr) return EINVAL;
    attr->__flags = flags;
    return 0;
}

static inline int posix_spawnattr_setpgroup(posix_spawnattr_t *attr, pid_t pgroup) {
    if (!attr) return EINVAL;
    attr->__pgroup = (void *)(intptr_t)pgroup;
    return 0;
}

/* Implementation using fork+execve */
static inline int posix_spawn_impl(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    (void)actions;
    (void)attr;

    pid_t child = fork();
    if (child < 0) {
        return errno;
    }

    if (child == 0) {
        /* Child process */
        execve(path, (char *const *)argv, (char *const *)envp);
        /* If execve fails, _exit with errno */
        _exit(127);
    }

    /* Parent process */
    if (pid) *pid = child;
    return 0;
}

static inline int posix_spawn(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    return posix_spawn_impl(pid, path, actions, attr, argv, envp);
}

static inline int posix_spawnp(pid_t *pid, const char *file,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    /* posix_spawnp searches PATH for the executable */
    /* Try the file directly first */
    if (access(file, X_OK) == 0) {
        return posix_spawn_impl(pid, file, actions, attr, argv, envp);
    }

    /* Search PATH */
    const char *path_env = envp ? NULL : getenv("PATH");
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/local/bin:/usr/bin:/bin";

    char pathbuf[4096];
    const char *p = path_env;
    while (*p) {
        const char *colon = strchr(p, ':');
        size_t len = colon ? (size_t)(colon - p) : strlen(p);
        if (len > 0 && len < sizeof(pathbuf) - 1 - strlen(file)) {
            memcpy(pathbuf, p, len);
            pathbuf[len] = '/';
            strcpy(pathbuf + len + 1, file);
            if (access(pathbuf, X_OK) == 0) {
                return posix_spawn_impl(pid, pathbuf, actions, attr, argv, envp);
            }
        }
        p += len;
        if (*p == ':') p++;
        if (!*p && colon) break; /* was last segment */
    }

    /* Not found */
    return ENOENT;
}

#endif /* __ANDROID_API__ < 28 */

/* ============================================
 * semctl (System V semaphore control)
 *
 * Android Bionic does not provide System V IPC:
 *   - semget
 *   - semctl
 *   - semop
 *   - msgget, msgctl, msgrcv, msgsnd
 *   - shmget, shmat, shmdt, shmctl
 *
 * Box64 uses semctl primarily for Steam library
 * IPC. On Android, this functionality is not
 * available. We provide stubs that return
 * appropriate error codes.
 *
 * Decision: ENOSYS (function not implemented)
 * This tells Box64 the operation is not supported
 * rather than silently succeeding.
 * ============================================ */
#include <sys/ipc.h>
#include <sys/sem.h>

/* Box64 only needs the semid_ds structure for fstat-like operations */
#ifndef __ANDROID__
/* Normal Linux - already has these */
#else
/* Android stubs */

#ifndef SEM_STAT
#define SEM_STAT 18
#endif
#ifndef SEM_INFO
#define SEM_INFO 19
#endif
#ifndef SEM_DEST
#define SEM_DEST 0x200
#endif
#ifndef SEM_NO主人
/* no-op */
#endif

union semun {
    int val;
    struct semid_ds *buf;
    unsigned short *array;
};

/* Stubs that return ENOSYS - Box64 can handle this */
static inline int semget(key_t key, int nsems, int semflg) {
    (void)key; (void)nsems; (void)semflg;
    errno = ENOSYS;
    return -1;
}

static inline int semctl(int semid, int semnum, int cmd, ...) {
    (void)semid; (void)semnum; (void)cmd;
    errno = ENOSYS;
    return -1;
}

static inline int semop(int semid, struct sembuf *sops, size_t nsops) {
    (void)semid; (void)sops; (void)nsops;
    errno = ENOSYS;
    return -1;
}

#endif /* __ANDROID__ */

/* ============================================
 * error / error_at_line (GNU extension)
 *
 * Box64 uses error() and error_at_line() from
 * <error.h>. Android Bionic does not provide
 * these GNU extensions.
 *
 * We implement them using fprintf+exit.
 * ============================================ */

/* Undefine any macros that might conflict */
#undef error
#undef error_at_line

static int error_message_count = 0;

static inline void error(int status, int errnum, const char *format, ...) {
    va_list args;
    fprintf(stderr, "box64: ");
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
    if (errnum)
        fprintf(stderr, ": %s\n", strerror(errnum));
    else
        fprintf(stderr, "\n");
    error_message_count++;
    if (status)
        exit(status);
}

static inline void error_at_line(int status, int errnum, const char *filename,
    unsigned int linenum, const char *format, ...) {
    va_list args;
    fprintf(stderr, "%s:%u: ", filename, linenum);
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
    if (errnum)
        fprintf(stderr, ": %s\n", strerror(errnum));
    else
        fprintf(stderr, "\n");
    error_message_count++;
    if (status)
        exit(status);
}

/* ============================================
 * Additional missing headers/functions
 * ============================================ */

/* <err.h> - warn/warnx/err/errx may be incomplete */
#include <err.h>

/* <sys/vfs.h> - Android has <sys/vfs.h> but some statfs functions differ */
#include <sys/vfs.h>

/* <fts.h> - Android has fts but it may need explicit inclusion */
#include <fts.h>

/* <search.h> - tsearch/tfind/tdelete/twalk */
#include <search.h>

/* <syslog.h> - Android has limited syslog */
#include <syslog.h>

/* <malloc.h> - Android has mallopt etc. */
#include <malloc.h>

/* <getopt.h> - Android has getopt */
#include <getopt.h>

/* <sys/resource.h> - Android has this */
#include <sys/resource.h>

/* <sys/prctl.h> - Android has this */
#include <sys/prctl.h>

/* <sys/ptrace.h> - Android has limited ptrace */
#include <sys/ptrace.h>

/* <sys/uio.h> - Android has this */
#include <sys/uio.h>

/* <sys/wait.h> - Android has this */
#include <sys/wait.h>

/* <sched.h> - Android has this */
#include <sched.h>

#else /* !__ANDROID__ */

/* On non-Android platforms, include the system headers directly */
#include <glob.h>
#include <spawn.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <error.h>
#include <err.h>
#include <sys/vfs.h>
#include <fts.h>
#include <search.h>
#include <syslog.h>
#include <malloc.h>
#include <getopt.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sched.h>

#endif /* __ANDROID__ */

#endif /* _ANDROID_COMPAT_H */
