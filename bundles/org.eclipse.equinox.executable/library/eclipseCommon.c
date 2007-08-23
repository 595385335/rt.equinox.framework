/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Andrew Niefer
 * Martin Oberhuber (Wind River) - [176805] Support Solaris9 by adding setenv()
 *******************************************************************************/
 
#include "eclipseCommon.h"
#include "eclipseUnicode.h"

#ifdef _WIN32
#include <direct.h>
#include <windows.h>
#else
#include <unistd.h>
#include <string.h>
#include <dirent.h>
#include <limits.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

/* Global Variables */
_TCHAR* osArg        = _T_ECLIPSE(DEFAULT_OS);
#ifdef MACOSX
	/* on the mac we have a universal binary, decide ppc vs x86 based on endianness */
	#ifdef __BIG_ENDIAN__
		_TCHAR* osArchArg    = _T_ECLIPSE("ppc");
	#elif __LITTLE_ENDIAN__
		_TCHAR* osArchArg    = _T_ECLIPSE("x86");
	#else
		_TCHAR* osArchArg    = _T_ECLIPSE(DEFAULT_OS_ARCH);
	#endif
#else
_TCHAR* osArchArg    = _T_ECLIPSE(DEFAULT_OS_ARCH);
#endif
_TCHAR* wsArg        = _T_ECLIPSE(DEFAULT_WS);	/* the SWT supported GUI to be used */

/* Local Variables */
#ifndef _WIN32
static _TCHAR* filterPrefix = NULL;  /* prefix for the find files filter */
#endif
static int     prefixLength = 0;

typedef struct {
	int segment[3];
	_TCHAR * qualifier;
} Version;

static void freeVersion(Version *version)
{
	if(version->qualifier)
		free(version->qualifier);
	free(version);
}

static Version* parseVersion(const _TCHAR * str) {
	_TCHAR *copy;
	_TCHAR *c1, *c2 = NULL;
	int i = 0;
	
	Version *version = malloc(sizeof(Version));
	memset(version, 0, sizeof(Version));
	
	c1 = copy = _tcsdup(str);
	while (c1 && *c1 != 0)
	{
		if (i < 3) {
			version->segment[i] = (int)_tcstol(c1, &c2, 10);
			/* if the next character is not '.', then we couldn't
			 * parse as a int, the remainder is not valid (or we are at the end)*/
			if (*c2 && *c2 != _T_ECLIPSE('.'))
				break;
			c2++; /* increment past the . */
		} else {
			c2 = _tcschr(c1, _T_ECLIPSE('.'));
			if(c2 != NULL) {
				*c2 = 0;
				version->qualifier = _tcsdup(c1);
				*c2 = _T_ECLIPSE('.'); /* put the dot back */
			} else {
				if(_tcsicmp(c1, _T_ECLIPSE("jar")) == 0)
					version->qualifier = 0;
				else
					version->qualifier = _tcsdup(c1);
			}
			break;
		}
		c1 = c2;
		i++;
	}
	free(copy);
	return version;
}

static int compareVersions(const _TCHAR* str1, const _TCHAR* str2) {
	int result = 0, i = 0;
	Version *v1 = parseVersion(str1);
	Version *v2 = parseVersion(str2);
	
	while (result == 0 && i < 3) {
		result = v1->segment[i] - v2->segment[i];
		i++;
	}
	if(result == 0) {
		_TCHAR * q1 = v1->qualifier ? v1->qualifier : _T_ECLIPSE("");
		_TCHAR * q2 = v2->qualifier ? v2->qualifier : _T_ECLIPSE("");
		result =  _tcscmp(q1, q2);
	}
	
	freeVersion(v1);
	freeVersion(v2);
	return result;
}

/**
 * Convert a wide string to a narrow one
 * Caller must free the null terminated string returned.
 */
char *toNarrow(_TCHAR* src)
{
#ifdef UNICODE
	int byteCount = WideCharToMultiByte (CP_ACP, 0, (wchar_t *)src, -1, NULL, 0, NULL, NULL);
	char *dest = malloc(byteCount+1);
	dest[byteCount] = 0;
	WideCharToMultiByte (CP_ACP, 0, (wchar_t *)src, -1, dest, byteCount, NULL, NULL);
	return dest;
#else
	return (char*)_tcsdup(src);
#endif
}


/**
 * Set an environment variable.
 * Solaris versions <= Solaris 9 did not know setenv in libc,
 * so emulate it here.
 */
#if defined(SOLARIS)
int setenv (const char *name, const char *value, int replace)
{
	int namelen, valuelen, rc;
	char *var;
	if (replace == 0) {
		const char *oldval = getenv(name);
		if (oldval != NULL) {
			return 0;
	    }
	}
	namelen = strlen(name);
	valuelen = strlen(value);
	var = malloc( (namelen + valuelen + 2) * sizeof(char) );
	if (var == NULL) {
		return -1;
	}
	/* Use strncpy as protection, in case a thread modifies var
	 * after we obtained its length */
	strncpy(var, name, namelen);
	var[namelen] = '=';
	strncpy( &var[namelen + 1], value, valuelen);
	var[namelen + valuelen + 1] = '\0';
	rc = putenv(var);
	if (rc != 0) rc = -1; /*putenv returns non-zero on error; setenv -1*/
	return rc;
}
#endif
 	
 /*
 * Find the absolute pathname to where a command resides.
 *
 * The string returned by the function must be freed.
 */
#define EXTRA 20
_TCHAR* findCommand( _TCHAR* command )
{
    _TCHAR*  cmdPath;
    int    length;
    _TCHAR*  ch;
    _TCHAR*  dir;
    _TCHAR*  path;
    struct _stat stats;

    /* If the command was an abolute pathname, use it as is. */
    if (command[0] == dirSeparator ||
       (_tcslen( command ) > 2 && command[1] == _T_ECLIPSE(':')))
    {
        length = _tcslen( command );
        cmdPath = malloc( (length + EXTRA) * sizeof(_TCHAR) ); /* add extra space for a possible ".exe" extension */
        _tcscpy( cmdPath, command );
    }

    else
    {
        /* If the command string contains a path separator */
        if (_tcschr( command, dirSeparator ) != NULL)
        {
            /* It must be relative to the current directory. */
            length = MAX_PATH_LENGTH + EXTRA + _tcslen( command );
            cmdPath = malloc( length * sizeof (_TCHAR));
            _tgetcwd( cmdPath, length );
            if (cmdPath[ _tcslen( cmdPath ) - 1 ] != dirSeparator)
            {
                length = _tcslen( cmdPath );
                cmdPath[ length ] = dirSeparator;
                cmdPath[ length+1 ] = _T_ECLIPSE('\0');
            }
            _tcscat( cmdPath, command );
        }

        /* else the command must be in the PATH somewhere */
        else
        {
            /* Get the directory PATH where executables reside. */
            path = _tgetenv( _T_ECLIPSE("PATH") );
            if (!path)
            {
	            return NULL;
            }
            else
            {
	            length = _tcslen( path ) + _tcslen( command ) + MAX_PATH_LENGTH;
	            cmdPath = malloc( length * sizeof(_TCHAR));
	
	            /* Foreach directory in the PATH */
	            dir = path;
	            while (dir != NULL && *dir != _T_ECLIPSE('\0'))
	            {
	                ch = _tcschr( dir, pathSeparator );
	                if (ch == NULL)
	                {
	                    _tcscpy( cmdPath, dir );
	                }
	                else
	                {
	                    length = ch - dir;
	                    _tcsncpy( cmdPath, dir, length );
	                    cmdPath[ length ] = _T_ECLIPSE('\0');
	                    ch++;
	                }
	                dir = ch; /* advance for the next iteration */

#ifdef _WIN32
                    /* Remove quotes */
	                if (_tcschr( cmdPath, _T_ECLIPSE('"') ) != NULL)
	                {
	                    int i = 0, j = 0, c;
	                    length = _tcslen( cmdPath );
	                    while (i < length) {
	                        c = cmdPath[ i++ ];
	                        if (c == _T_ECLIPSE('"')) continue;
	                        cmdPath[ j++ ] = c;
	                    }
	                    cmdPath[ j ] = _T_ECLIPSE('\0');
	                }
#endif
	                /* Determine if the executable resides in this directory. */
	                if (cmdPath[0] == _T_ECLIPSE('.') &&
	                   (_tcslen(cmdPath) == 1 || (_tcslen(cmdPath) == 2 && cmdPath[1] == dirSeparator)))
	                {
	                	_tgetcwd( cmdPath, MAX_PATH_LENGTH );
	                }
	                if (cmdPath[ _tcslen( cmdPath ) - 1 ] != dirSeparator)
	                {
	                    length = _tcslen( cmdPath );
	                    cmdPath[ length ] = dirSeparator;
	                    cmdPath[ length+1 ] = _T_ECLIPSE('\0');
	                }
	                _tcscat( cmdPath, command );
	
	                /* If the file is not a directory and can be executed */
	                if (_tstat( cmdPath, &stats ) == 0 && (stats.st_mode & S_IFREG) != 0)
	                {
	                    /* Stop searching */
	                    dir = NULL;
	                }
	            }
	        }
        }
    }

#ifdef _WIN32
	/* If the command does not exist */
    if (_tstat( cmdPath, &stats ) != 0 || (stats.st_mode & S_IFREG) == 0)
    {
    	/* If the command does not end with .exe, append it an try again. */
    	length = _tcslen( cmdPath );
    	if (length > 4 && _tcsicmp( &cmdPath[ length - 4 ], _T_ECLIPSE(".exe") ) != 0)
    	    _tcscat( cmdPath, _T_ECLIPSE(".exe") );
    }
#endif

    /* Verify the resulting command actually exists. */
    if (_tstat( cmdPath, &stats ) != 0 || (stats.st_mode & S_IFREG) == 0)
    {
        free( cmdPath );
        cmdPath = NULL;
        return cmdPath;
    }

	ch = resolveSymlinks(cmdPath);
	if (ch != cmdPath) {
		free(cmdPath);
		cmdPath = ch;
	}
	return cmdPath;
}

#if !defined(_WIN32) && !defined(MACOSX)
char * resolveSymlinks( char * path ) {
	char * ch, *buffer;
	if(path == NULL)
		return path;
	/* resolve symlinks */
	ch = path;
	buffer = malloc(PATH_MAX);
    path = realpath(path, buffer);
    return path;
}
#endif

#ifndef _WIN32
#ifdef MACOSX
static int filter(struct dirent *dir) {
#else
static int filter(const struct dirent *dir) {
#endif
	char *c1, *c2;
	
	if(_tcslen(dir->d_name) <= prefixLength)
		return 0;
	if (_tcsncmp(dir->d_name, filterPrefix, prefixLength) == 0 &&
		dir->d_name[prefixLength] == '_') 
	{
		c1 = strchr(&dir->d_name[prefixLength + 1], '_');
		if(c1 != NULL) {
			c2 = strchr(&dir->d_name[prefixLength + 1], '.');
			if (c2 != NULL) {
				return c2 < c1;
			} else 
				return 0;
		} else 
			return 1;
	}
	return 0;
}
#endif
 /* 
 * Looks for files of the form /path/prefix_version.<extension> and returns the full path to
 * the file with the largest version number
 */ 
_TCHAR* findFile( _TCHAR* path, _TCHAR* prefix)
{
	struct _stat stats;
	int pathLength;
	_TCHAR* candidate = NULL;
	_TCHAR* result = NULL;
	
#ifdef _WIN32
	_TCHAR* fileName = NULL;
	WIN32_FIND_DATA data;
	HANDLE handle;
#else	
	DIR *dir = NULL;
	struct dirent * entry = NULL;
#endif
	
	path = _tcsdup(path);
	pathLength = _tcslen(path);
	
	/* strip dirSeparators off the end */
	while (path[pathLength - 1] == dirSeparator) {
		path[--pathLength] = 0;
	}
	
	/* does path exist? */
	if( _tstat(path, &stats) != 0 ) {
		free(path);
		return NULL;
	}
	
#ifdef _WIN32
	fileName = malloc( (_tcslen(path) + 1 + _tcslen(prefix) + 3) * sizeof(_TCHAR));
	_stprintf(fileName, _T_ECLIPSE("%s%c%s_*"), path, dirSeparator, prefix);
	prefixLength = _tcslen(prefix);
	
	handle = FindFirstFile(fileName, &data);
	if(handle != INVALID_HANDLE_VALUE) {
		candidate = _tcsdup(data.cFileName);
		while(FindNextFile(handle, &data) != 0) {
			fileName = data.cFileName;
			/* compare, take the highest version */
			if( compareVersions(candidate + prefixLength + 1, fileName + prefixLength + 1) < 0) {
				free(candidate);
				candidate = _tcsdup(fileName);
			}
		}
		FindClose(handle);
	}
#else
	filterPrefix = prefix;
	prefixLength = _tcslen(prefix);
	if ((dir = opendir(path)) == NULL) {
		free(path);
		return NULL;
	}

	while ((entry = readdir(dir)) != NULL) {
		if (filter(entry)) {
			if (candidate == NULL) {
				candidate = _tcsdup(entry->d_name);
			} else if (compareVersions(candidate + prefixLength + 1, entry->d_name + prefixLength + 1) < 0) {
				free(candidate);
				candidate = _tcsdup(entry->d_name);
			}
		}
	}
	closedir(dir);
#endif

	if(candidate != NULL) {
		result = malloc((pathLength + 1 + _tcslen(candidate) + 1) * sizeof(_TCHAR));
		_tcscpy(result, path);
		result[pathLength] = dirSeparator;
		result[pathLength + 1] = 0;
		_tcscat(result, candidate);
		free(candidate);
	}
	free(path);
	return result;
}

/*
 * If path is relative, attempt to make it absolute by 
 * 1) check relative to working directory
 * 2) check relative to provided programDir
 * If reverseOrder, then check the programDir before the working dir
 */
_TCHAR* checkPath( _TCHAR* path, _TCHAR* programDir, int reverseOrder ) 
{
	int cwdSize = MAX_PATH_LENGTH * sizeof(_TCHAR);
	int i;
	_TCHAR * workingDir, * buffer, * result = NULL;
	_TCHAR * paths[2];
	struct _stat stats;
	
	/* If the command was an abolute pathname, use it as is. */
    if (path[0] == dirSeparator ||
       (_tcslen( path ) > 2 && path[1] == _T_ECLIPSE(':')))
    {
    	return path;
    }
    
    /* get the current working directory */
    workingDir = malloc(cwdSize);
    while ( _tgetcwd( workingDir, cwdSize ) == NULL ){
    	cwdSize *= 2;
    	workingDir = realloc(workingDir, cwdSize);
    }
    
    paths[0] = reverseOrder ? programDir : workingDir;
    paths[1] = reverseOrder ? workingDir : programDir;
    
    /* just make a buffer big enough to hold everything */
    buffer = malloc((_tcslen(paths[0]) + _tcslen(paths[1]) + _tcslen(path) + 2) * sizeof(_TCHAR));
    for ( i = 0; i < 2; i++ ) {
    	_stprintf(buffer, _T_ECLIPSE("%s%c%s"), paths[i], dirSeparator, path);
    	if (_tstat(buffer, &stats) == 0) {
    		result = _tcsdup(buffer);
    		break;
    	}
    }
    
    free(buffer);
    free(workingDir);
    
    /* if we found something, return it, otherwise, return the original */
    return result != NULL ? result : path;
}

