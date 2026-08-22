/*
 * Android Bionic Compatibility Layer for Box64
 *
 * This header provides POSIX APIs missing from Android Bionic that Box64 needs.
 * It does NOT redefine any NDK-owned types.
 *
 * Functions provided for API < 28:
 *   - posix_spawn / posix_spawnp (via fork+execve)
 *   - posix_spawn_file_actions_* helpers
 *   - posix_spawnattr_* helpers
 *
 * Functions provided (System V IPC stubs):
 *   - semctl / semget / semop (return ENOSYS)
 *
 * License: MIT
 */

#ifndef _ANDROID_COMPAT_H
#define _ANDROID_COMPAT_H

#ifdef __ANDROID__

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
 * The NDK provides these only for API >= 28.
 * For API < 28, we provide implementations using
 * fork+execve.
 *
 * IMPORTANT: We do NOT redefine the types
 * posix_spawn_file_actions_t or posix_spawnattr_t.
 * The NDK headers define them as opaque pointer types:
 *   typedef struct __posix_spawnattr* posix_spawnattr_t;
 *   typedef struct __posix_spawn_file_actions* posix_spawn_file_actions_t;
 *
 * We use the NDK's type definitions and provide
 * function implementations that work with them.
 * ============================================ */

#if __ANDROID_API__ < 28

#include <spawn.h>

/*
 * For API < 28, the NDK header declares the types but gates
 * the functions behind __BIONIC_AVAILABILITY__(28).
 *
 * We provide our own implementations that work with the
 * opaque pointer types defined by the NDK.
 */

/* Forward declare the opaque types (already defined by NDK header) */
/* struct __posix_spawn_file_actions; */
/* struct __posix_spawnattr; */

/* Our internal storage for file actions */
struct _android_spawn_file_action {
    int type;           /* 0=none, 1=close, 2=dup2, 3=open */
    int fd;
    int newfd;
    int flags;
    mode_t mode;
    char path[256];
};

struct _android_spawn_file_actions {
    struct _android_spawn_file_action actions[32];
    int count;
};

struct _android_spawn_attr {
    unsigned int flags;
    pid_t pgroup;
};

/* Cast helpers - these work with the NDK's opaque pointer types */
#define _CAST_ACTIONS(a) ((struct _android_spawn_file_actions*)(a))
#define _CAST_ATTR(a) ((struct _android_spawn_attr*)(a))

static inline int posix_spawn_file_actions_init(posix_spawn_file_actions_t *actions) {
    if (!actions) return EINVAL;
    struct _android_spawn_file_actions *a = _CAST_ACTIONS(*actions);
    if (!a) {
        a = (struct _android_spawn_file_actions*)calloc(1, sizeof(struct _android_spawn_file_actions));
        if (!a) return ENOMEM;
        *actions = (posix_spawn_file_actions_t)a;
    }
    a->count = 0;
    return 0;
}

static inline int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *actions) {
    (void)actions;
    return 0;
}

static inline int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *actions, int fd) {
    if (!actions || !*actions) return EINVAL;
    struct _android_spawn_file_actions *a = _CAST_ACTIONS(*actions);
    if (a->count >= 32) return E2BIG;
    int i = a->count;
    a->actions[i].type = 1;
    a->actions[i].fd = fd;
    a->count++;
    return 0;
}

static inline int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *actions, int fd, int newfd) {
    if (!actions || !*actions) return EINVAL;
    struct _android_spawn_file_actions *a = _CAST_ACTIONS(*actions);
    if (a->count >= 32) return E2BIG;
    int i = a->count;
    a->actions[i].type = 2;
    a->actions[i].fd = fd;
    a->actions[i].newfd = newfd;
    a->count++;
    return 0;
}

static inline int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *actions, int fd,
    const char *path, int flags, mode_t mode) {
    if (!actions || !*actions || !path) return EINVAL;
    struct _android_spawn_file_actions *a = _CAST_ACTIONS(*actions);
    if (a->count >= 32) return E2BIG;
    int i = a->count;
    a->actions[i].type = 3;
    a->actions[i].fd = fd;
    a->actions[i].flags = flags;
    a->actions[i].mode = mode;
    strncpy(a->actions[i].path, path, sizeof(a->actions[i].path) - 1);
    a->count++;
    return 0;
}

static inline int posix_spawnattr_init(posix_spawnattr_t *attr) {
    if (!attr) return EINVAL;
    struct _android_spawn_attr *a = _CAST_ATTR(*attr);
    if (!a) {
        a = (struct _android_spawn_attr*)calloc(1, sizeof(struct _android_spawn_attr));
        if (!a) return ENOMEM;
        *attr = (posix_spawnattr_t)a;
    }
    memset(a, 0, sizeof(struct _android_spawn_attr));
    return 0;
}

static inline int posix_spawnattr_destroy(posix_spawnattr_t *attr) {
    (void)attr;
    return 0;
}

static inline int posix_spawnattr_setflags(posix_spawnattr_t *attr, unsigned short flags) {
    if (!attr || !*attr) return EINVAL;
    _CAST_ATTR(*attr)->flags = flags;
    return 0;
}

static inline int posix_spawnattr_setpgroup(posix_spawnattr_t *attr, pid_t pgroup) {
    if (!attr || !*attr) return EINVAL;
    _CAST_ATTR(*attr)->pgroup = pgroup;
    return 0;
}

/* Internal implementation using fork+execve */
static inline int _android_posix_spawn_impl(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    (void)attr;

    pid_t child = fork();
    if (child < 0) return errno;

    if (child == 0) {
        /* Child process - apply file actions */
        if (actions && *actions) {
            struct _android_spawn_file_actions *a = _CAST_ACTIONS(*actions);
            for (int i = 0; i < a->count; i++) {
                switch (a->actions[i].type) {
                    case 1: /* close */
                        close(a->actions[i].fd);
                        break;
                    case 2: /* dup2 */
                        dup2(a->actions[i].fd, a->actions[i].newfd);
                        break;
                    case 3: /* open */
                        close(a->actions[i].fd);
                        open(a->actions[i].path, a->actions[i].flags, a->actions[i].mode);
                        break;
                }
            }
        }
        execve(path, (char *const *)argv, (char *const *)envp);
        _exit(127);
    }

    if (pid) *pid = child;
    return 0;
}

static inline int posix_spawn(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    return _android_posix_spawn_impl(pid, path, actions, attr, argv, envp);
}

static inline int posix_spawnp(pid_t *pid, const char *file,
    const posix_spawn_file_actions_t *actions,
    const posix_spawnattr_t *attr,
    char *const argv[], char *const envp[])
{
    /* Try the file directly first */
    if (access(file, X_OK) == 0) {
        return _android_posix_spawn_impl(pid, file, actions, attr, argv, envp);
    }

    /* Search PATH */
    const char *path_env = getenv("PATH");
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
                return _android_posix_spawn_impl(pid, pathbuf, actions, attr, argv, envp);
            }
        }
        p += len;
        if (*p == ':') p++;
        if (!*p) break;
    }

    return ENOENT;
}

#endif /* __ANDROID_API__ < 28 */

/* ============================================
 * semctl / semget / semop (System V IPC)
 *
 * Android Bionic does not provide System V IPC.
 * Box64 uses these for Steam library IPC.
 * On Android, this functionality is unavailable.
 *
 * We return ENOSYS (function not implemented)
 * so Box64 can handle this gracefully.
 * ============================================ */
#ifndef _SYS_SEM_H
#include <sys/sem.h>
#endif

#ifdef __ANDROID__
/* Only define stubs if semctl is not already defined */
/* Android Bionic does not provide these, so we define them */

#ifndef _SYS_IPC_H
#include <sys/ipc.h>
#endif

/* semun union - Android doesn't define it, but Box64 may use it */
#ifndef _SEMUN_DEFINED
#define _SEMUN_DEFINED
union semun {
    int val;
    struct semid_ds *buf;
    unsigned short *array;
};
#endif

/* Stubs */
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
 * ============================================ */
#ifdef __ANDROID__

/* Only define if not already provided */
#ifndef _ERROR_H
#define _ERROR_H

static int _android_error_count = 0;

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
    _android_error_count++;
    if (status) exit(status);
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
    _android_error_count++;
    if (status) exit(status);
}

#endif /* _ERROR_H */

#endif /* __ANDROID__ */

#else /* !__ANDROID__ */

/* On non-Android platforms, include system headers */
#include <glob.h>
#include <spawn.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <error.h>

#endif /* __ANDROID__ */

#endif /* _ANDROID_COMPAT_H */
