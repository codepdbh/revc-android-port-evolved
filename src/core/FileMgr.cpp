#define _CRT_SECURE_NO_WARNINGS
#include <fcntl.h>
#ifdef _WIN32
#include <direct.h>
#endif
#ifdef __APPLE__
#include <mach-o/dyld.h>
#include <sys/stat.h>
#endif
#include "common.h"
#include "crossplatform.h"

#include "FileMgr.h"

const char *_psGetUserFilesFolder();

/*
 * Windows FILE is BROKEN for GTA.
 *
 * We need to support mapping between LF and CRLF for text files
 * but we do NOT want to end the file at the first sight of a SUB character.
 * So here is a simple implementation of a FILE interface that works like GTA expects.
 */

struct myFILE
{
	bool isText;
	FILE *file;
};

#define NUMFILES 20
static myFILE myfiles[NUMFILES];


#if !defined(_WIN32)
#include <dirent.h>
#include <errno.h>
#include <unistd.h>
#define _getcwd getcwd

// Case-insensitivity on linux (from https://github.com/OneSadCookie/fcaseopen)
void mychdir(char const *path)
{
#if defined(ANDROID)
	if(!path) {
        return;
    }
#endif
	char* r = casepath(path, false);
    if (r) {
#if defined(ANDROID)
		char path[MAX_PATH];
		strcpy(path, CFileMgr::GetRootDirName());
		strcat(path, r);
        chdir(path);
#else
        chdir(r);
#endif
		free(r);
    } else {
        errno = ENOENT;
    }
}
#else
#define mychdir chdir
#endif

#ifdef __APPLE__
static char bundledGameFilesDir[FILEMGR_PATH_SIZE] = {'\0'};

static bool
GameFileExists(const char *root, const char *name)
{
	char path[FILEMGR_PATH_SIZE];
	int length = snprintf(path, sizeof(path), "%s%s%s", root,
		root[0] != '\0' && root[strlen(root) - 1] == '/' ? "" : "/", name);
	if(length < 0 || length >= (int)sizeof(path))
		return false;

	FILE *file = fcaseopen(path, "rb");
	if(file == nil)
		return false;
	fclose(file);
	return true;
}

static bool
SetGameRoot(const char *root, char *path, size_t pathSize)
{
	char realRoot[FILEMGR_PATH_SIZE];
	if(realpath(root, realRoot) == nil ||
	   !GameFileExists(realRoot, "data/gta_vc.dat") ||
	   !GameFileExists(realRoot, "models/gta3.img"))
		return false;

	int length = snprintf(path, pathSize, "%s/", realRoot);
	return length >= 0 && length < (int)pathSize;
}

static bool
GetExecutableDir(char *path, size_t pathSize)
{
	char executable[FILEMGR_PATH_SIZE];
	uint32 size = sizeof(executable);
	if(_NSGetExecutablePath(executable, &size) != 0)
		return false;

	char *slash = strrchr(executable, '/');
	if(slash == nil)
		return false;
	*slash = '\0';

	char realPath[FILEMGR_PATH_SIZE];
	const char *dir = realpath(executable, realPath) == nil ? executable : realPath;
	int length = snprintf(path, pathSize, "%s", dir);
	return length >= 0 && length < (int)pathSize;
}

static bool
FindBundledGameFiles(char *path, size_t pathSize)
{
	char executableDir[FILEMGR_PATH_SIZE];
	if(!GetExecutableDir(executableDir, sizeof(executableDir)))
		return false;

	char bundleDir[FILEMGR_PATH_SIZE];
	int length = snprintf(bundleDir, sizeof(bundleDir),
		"%s/../Resources/gamefiles", executableDir);
	if(length < 0 || length >= (int)sizeof(bundleDir))
		return false;

	char realBundleDir[FILEMGR_PATH_SIZE];
	struct stat status;
	if(realpath(bundleDir, realBundleDir) == nil ||
	   stat(realBundleDir, &status) != 0 || !S_ISDIR(status.st_mode))
		return false;

	length = snprintf(path, pathSize, "%s", realBundleDir);
	return length >= 0 && length < (int)pathSize;
}

static bool
ReadGameRoot(char *path, size_t pathSize)
{
	char root[FILEMGR_PATH_SIZE];
	if(!ReadGamePathFromINI(root, sizeof(root)))
		return false;
	return SetGameRoot(root, path, pathSize);
}

static void
WriteGameRoot(const char *root)
{
	WriteGamePathToINI(root);
}

static bool
ChooseGameRoot(char *path, size_t pathSize)
{
	bool retry = false;
	for(;;){
		const char *prompt = retry ?
			"That folder is not a GTA Vice City installation. Choose the folder containing the data and models folders." :
			"Choose the GTA Vice City installation folder.";
		char command[512];
		snprintf(command, sizeof(command),
			"/usr/bin/osascript -e 'POSIX path of (choose folder with prompt \"%s\")' 2>/dev/null", prompt);

		FILE *pipe = popen(command, "r");
		if(pipe == nil)
			return false;

		char root[FILEMGR_PATH_SIZE];
		bool found = fgets(root, sizeof(root), pipe) != nil;
		int status = pclose(pipe);
		if(!found || status != 0)
			return false;

		root[strcspn(root, "\r\n")] = '\0';
		if(SetGameRoot(root, path, pathSize)){
			WriteGameRoot(path);
			return true;
		}
		retry = true;
	}
}

static bool
FindGameRoot(char *path, size_t pathSize)
{
	char executableDir[FILEMGR_PATH_SIZE];
	if(GetExecutableDir(executableDir, sizeof(executableDir))){
		if(SetGameRoot(executableDir, path, pathSize))
			return true;

		char defaultRoot[FILEMGR_PATH_SIZE];
		int length = snprintf(defaultRoot, sizeof(defaultRoot), "%s/../../..", executableDir);
		if(length >= 0 && length < (int)sizeof(defaultRoot) &&
		   SetGameRoot(defaultRoot, path, pathSize))
			return true;
	}

	char cwd[FILEMGR_PATH_SIZE];
	if(_getcwd(cwd, sizeof(cwd)) != nil && SetGameRoot(cwd, path, pathSize))
		return true;

	return ReadGameRoot(path, pathSize) || ChooseGameRoot(path, pathSize);
}
#endif

/* Force file to open as binary but remember if it was text mode */
static int
myfopen(const char *filename, const char *mode)
{
	int fd;
	char realmode[10], *p;
	const char *openPath = filename;
#ifdef __APPLE__
	char bundledPath[FILEMGR_PATH_SIZE];
	if(mode[0] == 'r' && strchr(mode, '+') == nil &&
	   CFileMgr::ResolveBundledGameFile(filename, bundledPath, sizeof(bundledPath)))
		openPath = bundledPath;
#endif

	for(fd = 1; fd < NUMFILES; fd++)
		if(myfiles[fd].file == nil)
			goto found;
	return 0;	// no free fd
found:
	myfiles[fd].isText = strchr(mode, 'b') == nil;
	p = realmode;
	while(*mode)
		if(*mode != 't' && *mode != 'b')
			*p++ = *mode++;
		else
			mode++;
	*p++ = 'b';
	*p = '\0';
	
	myfiles[fd].file = fcaseopen(openPath, realmode);
	if(myfiles[fd].file == nil)
		return 0;
	return fd;
}

static int
myfclose(int fd)
{
	int ret;
	assert(fd < NUMFILES);
	if(myfiles[fd].file){
		ret = fclose(myfiles[fd].file);
		myfiles[fd].file = nil;
		return ret;
	}
	return EOF;
}

static int
myfgetc(int fd)
{
	int c;
	c = fgetc(myfiles[fd].file);
	if(myfiles[fd].isText && c == 015){
		/* translate CRLF to LF */
		c = fgetc(myfiles[fd].file);
		if(c == 012)
			return c;
		ungetc(c, myfiles[fd].file);
		return 015;
	}
	return c;
}

static int
myfputc(int c, int fd)
{
	/* translate LF to CRLF */
	if(myfiles[fd].isText && c == 012)
		fputc(015, myfiles[fd].file);
	return fputc(c, myfiles[fd].file);
}

static char*
myfgets(char *buf, int len, int fd)
{
	int c;
	char *p;

	p = buf;
	len--;	// NUL byte
	while(len--){
		c = myfgetc(fd);
		if(c == EOF){
			if(p == buf)
				return nil;
			break;
		}
		*p++ = c;
		if(c == '\n')
			break;
	}
	*p = '\0';
	return buf;
}

static size_t
myfread(void *buf, size_t elt, size_t n, int fd)
{
	if(myfiles[fd].isText){
		unsigned char *p;
		size_t i;
		int c;

		n *= elt;
		p = (unsigned char*)buf;
		for(i = 0; i < n; i++){
			c = myfgetc(fd);
			if(c == EOF)
				break;
			*p++ = (unsigned char)c;
		}
		return i / elt;
	}
	return fread(buf, elt, n, myfiles[fd].file);
}

static size_t
myfwrite(void *buf, size_t elt, size_t n, int fd)
{
	if(myfiles[fd].isText){
		unsigned char *p;
		size_t i;
		int c;

		n *= elt;
		p = (unsigned char*)buf;
		for(i = 0; i < n; i++){
			c = *p++;
			myfputc(c, fd);
			if(feof(myfiles[fd].file))	// is this right?
				break;
		}
		return i / elt;
	}
	return fwrite(buf, elt, n, myfiles[fd].file);
}

static int
myfseek(int fd, long offset, int whence)
{
	return fseek(myfiles[fd].file, offset, whence);
}

static int
myfeof(int fd)
{
	return feof(myfiles[fd].file);
//	return ferror(myfiles[fd].file);
}


char CFileMgr::ms_rootDirName[FILEMGR_PATH_SIZE] = {'\0'};
char CFileMgr::ms_dirName[FILEMGR_PATH_SIZE];

void
CFileMgr::Initialise(void)
{
#if defined(ANDROID)
	if(getenv("STORAGE_ROOT") != NULL) {
		strcpy(ms_rootDirName, getenv("STORAGE_ROOT"));
		strcat(ms_rootDirName, "/");
        debug("Android: Root Dir: %s\n", ms_rootDirName);
	}
#elif defined(__APPLE__)
	FindBundledGameFiles(bundledGameFilesDir, sizeof(bundledGameFilesDir));
	if(ms_rootDirName[0] == '\0' && !FindGameRoot(ms_rootDirName, sizeof(ms_rootDirName)))
		_exit(0);
	strcpy(ms_dirName, ms_rootDirName);
	mychdir(ms_rootDirName);
#else
	_getcwd(ms_rootDirName, sizeof(ms_rootDirName));
	strcat(ms_rootDirName, "\\");
#endif
}

bool
CFileMgr::ResolveBundledGameFile(const char *file, char *path, size_t pathSize)
{
#ifdef __APPLE__
	const char *rootDir = CFileMgr::GetRootDirName();
	if(bundledGameFilesDir[0] == '\0' || rootDir[0] == '\0' ||
	   file[0] == '\0' || file[0] == '/' || file[0] == '\\')
		return false;

	char cwd[FILEMGR_PATH_SIZE];
	if(_getcwd(cwd, sizeof(cwd)) == nil)
		return false;

	size_t rootLength = strlen(rootDir);
	while(rootLength > 0 && rootDir[rootLength - 1] == '/')
		rootLength--;
	if(strncmp(cwd, rootDir, rootLength) != 0 ||
	   (cwd[rootLength] != '\0' && cwd[rootLength] != '/'))
		return false;

	char candidate[FILEMGR_PATH_SIZE];
	int length = snprintf(candidate, sizeof(candidate), "%s%s/%s",
		bundledGameFilesDir, cwd + rootLength, file);
	if(length < 0 || length >= (int)sizeof(candidate))
		return false;

	char *realPath = casepath(candidate);
	const char *resolvedPath = realPath == nil ? candidate : realPath;
	FILE *bundleFile = fopen(resolvedPath, "rb");
	if(bundleFile == nil){
		free(realPath);
		return false;
	}
	fclose(bundleFile);

	length = snprintf(path, pathSize, "%s", resolvedPath);
	free(realPath);
	return length >= 0 && length < (int)pathSize;
#else
	(void)file;
	(void)path;
	(void)pathSize;
	return false;
#endif
}

void
CFileMgr::ChangeDir(const char *dir)
{
	if(*dir == '\\'){
		strcpy(ms_dirName, ms_rootDirName);
		dir++;
	}
	if(*dir != '\0'){
		strcat(ms_dirName, dir);
#ifndef ANDROID
        // BUG in the game it seems, it's off by one
		if(dir[strlen(dir)-1] != '\\')
			strcat(ms_dirName, "\\");
#endif
	}
	debug("CFileMgr::ChangeDir: %s", ms_dirName);
	mychdir(ms_dirName);
}

void
CFileMgr::SetDir(const char *dir)
{
	strcpy(ms_dirName, ms_rootDirName);
	if(*dir != '\0'){
		strcat(ms_dirName, dir);
#ifndef ANDROID
        // BUG in the game it seems, it's off by one
		if(dir[strlen(dir)-1] != '\\')
			strcat(ms_dirName, "\\");
#endif
	}
	debug("CFileMgr::SetDir: %s", ms_dirName);
	mychdir(ms_dirName);
}

void
CFileMgr::SetDirMyDocuments(void)
{
	SetDir("");	// better start at the root if user directory is relative
	mychdir(_psGetUserFilesFolder());
}

ssize_t
CFileMgr::LoadFile(const char *file, uint8 *buf, int maxlen, const char *mode)
{
	int fd;
	ssize_t n, len;

	fd = myfopen(file, mode);
	if(fd == 0)
		return -1;
	len = 0;
	do{
		n = myfread(buf + len, 1, 0x4000, fd);
#ifndef FIX_BUGS
		if (n < 0)
			return -1;
#endif
		len += n;
		assert(len < maxlen);
	}while(n == 0x4000);
	buf[len] = 0;
	myfclose(fd);
	return len;
}

int
CFileMgr::OpenFile(const char *file, const char *mode)
{
	debug("CFileMgr::OpenFile: %s", file);
	return myfopen(file, mode);
}

int
CFileMgr::OpenFileForWriting(const char *file)
{
	debug("CFileMgr::OpenFileForWriting: %s", file);
	return OpenFile(file, "wb");
}

size_t
CFileMgr::Read(int fd, char *buf, ssize_t len)
{
	return myfread((void*)buf, 1, len, fd);
}

size_t
CFileMgr::Write(int fd, const char *buf, ssize_t len)
{
	return myfwrite((void*)buf, 1, len, fd);
}

bool
CFileMgr::Seek(int fd, int offset, int whence)
{
	return !!myfseek(fd, offset, whence);
}

bool
CFileMgr::ReadLine(int fd, char *buf, int len)
{
	return myfgets(buf, len, fd) != nil;
}

int
CFileMgr::CloseFile(int fd)
{
	return myfclose(fd);
}

int
CFileMgr::GetErrorReadWrite(int fd)
{
	return myfeof(fd);
}
