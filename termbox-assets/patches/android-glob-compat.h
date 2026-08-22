/*
 * Android Bionic Compatibility: glob() / globfree()
 *
 * Android's Bionic libc does not provide glob() or globfree().
 * This is a minimal implementation sufficient for Box64's src/steam.c usage.
 *
 * Only supports the flags and patterns actually used by Box64:
 *   - Basic filename expansion with wildcards (*, ?)
 *   - GLOB_NOCHECK (return pattern itself if no matches)
 *   - gl_pathc, gl_pathv output fields
 *
 * Based on POSIX.1-2001 specification.
 * License: MIT (compatible with Box64's license)
 */

#ifndef _ANDROID_GLOB_COMPAT_H
#define _ANDROID_GLOB_COMPAT_H

#ifdef __ANDROID__

#include <sys/types.h>
#include <dirent.h>
#include <string.h>
#include <stdlib.h>
#include <fnmatch.h>
#include <errno.h>

#define GLOB_ERR      (1 << 0)
#define GLOB_MARK     (1 << 1)
#define GLOB_NOSORT   (1 << 2)
#define GLOB_DOOFFS   (1 << 3)
#define GLOB_NOCHECK  (1 << 4)
#define GLOB_APPEND   (1 << 5)
#define GLOB_NOESCAPE (1 << 6)

#define GLOB_NOSPACE  1
#define GLOB_ABORTED  2
#define GLOB_NOMATCH  3

typedef struct {
    size_t   gl_pathc;    /* Count of matched pathnames */
    char   **gl_pathv;    /* List of matched pathnames */
    size_t   gl_offs;     /* Slots to reserve in gl_pathv */
} glob_t;

static inline int glob(const char *pattern, int flags,
                        int (*errfunc)(const char *, int),
                        glob_t *pglob)
{
    DIR *dir;
    struct dirent *entry;
    const char *dirpath;
    const char *basepat;
    char **results = NULL;
    size_t count = 0;
    size_t alloc = 0;
    char dirbuf[4096];

    /* Find the directory portion of the pattern */
    const char *lastslash = strrchr(pattern, '/');
    if (lastslash) {
        size_t dirlen = lastslash - pattern;
        if (dirlen >= sizeof(dirbuf)) dirlen = sizeof(dirbuf) - 1;
        memcpy(dirbuf, pattern, dirlen);
        dirbuf[dirlen] = '\0';
        dirpath = dirbuf;
        basepat = lastslash + 1;
    } else {
        dirpath = ".";
        basepat = pattern;
    }

    /* If pattern is empty or just ".", treat as direct match */
    if (basepat[0] == '\0') {
        basepat = "*";
    }

    dir = opendir(dirpath);
    if (!dir) {
        if (flags & GLOB_NOCHECK) {
            pglob->gl_pathc = 1;
            pglob->gl_pathv = (char **)malloc(2 * sizeof(char *));
            if (!pglob->gl_pathv) return GLOB_NOSPACE;
            pglob->gl_pathv[0] = strdup(pattern);
            pglob->gl_pathv[1] = NULL;
            return 0;
        }
        return GLOB_NOMATCH;
    }

    alloc = 16;
    results = (char **)malloc(alloc * sizeof(char *));
    if (!results) {
        closedir(dir);
        return GLOB_NOSPACE;
    }
    count = 0;

    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.' && basepat[0] != '.') {
            continue; /* Skip hidden files unless pattern starts with . */
        }
        if (fnmatch(basepat, entry->d_name, 0) == 0) {
            if (count >= alloc) {
                alloc *= 2;
                char **newresults = (char **)realloc(results, alloc * sizeof(char *));
                if (!newresults) {
                    for (size_t i = 0; i < count; i++) free(results[i]);
                    free(results);
                    closedir(dir);
                    return GLOB_NOSPACE;
                }
                results = newresults;
            }
            size_t pathlen = strlen(dirpath) + 1 + strlen(entry->d_name) + 1;
            char *fullpath = (char *)malloc(pathlen);
            if (!fullpath) {
                for (size_t i = 0; i < count; i++) free(results[i]);
                free(results);
                closedir(dir);
                return GLOB_NOSPACE;
            }
            if (strcmp(dirpath, ".") == 0) {
                snprintf(fullpath, pathlen, "%s", entry->d_name);
            } else {
                snprintf(fullpath, pathlen, "%s/%s", dirpath, entry->d_name);
            }
            results[count++] = fullpath;
        }
    }
    closedir(dir);

    if (count == 0 && (flags & GLOB_NOCHECK)) {
        results[count++] = strdup(pattern);
    }

    if (count == 0) {
        free(results);
        return GLOB_NOMATCH;
    }

    results[count] = NULL;
    pglob->gl_pathc = count;
    pglob->gl_pathv = results;
    return 0;
}

static inline void globfree(glob_t *pglob)
{
    if (pglob->gl_pathv) {
        for (size_t i = 0; i < pglob->gl_pathc; i++) {
            free(pglob->gl_pathv[i]);
        }
        free(pglob->gl_pathv);
        pglob->gl_pathv = NULL;
    }
    pglob->gl_pathc = 0;
}

#else
/* On non-Android platforms, use the system glob.h */
#include <glob.h>
#endif /* __ANDROID__ */

#endif /* _ANDROID_GLOB_COMPAT_H */
