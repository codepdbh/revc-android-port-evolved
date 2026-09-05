#ifndef __GTA_FILEMGR_H__
#define __GTA_FILEMGR_H__

#define FILEMGR_PATH_SIZE 1024

class CFileMgr
{
	static char ms_rootDirName[FILEMGR_PATH_SIZE];
	static char ms_dirName[FILEMGR_PATH_SIZE];
public:
	static void Initialise(void);
	static void ChangeDir(const char *dir);
	static void SetDir(const char *dir);
	static void SetDirMyDocuments(void);
	static ssize_t LoadFile(const char *file, uint8 *buf, int maxlen, const char *mode);
	static int OpenFile(const char *file, const char *mode);
	static int OpenFile(const char *file) { return OpenFile(file, "rb"); }
	static int OpenFileForWriting(const char *file);
	static size_t Read(int fd, char *buf, ssize_t len);
	static size_t Write(int fd, const char *buf, ssize_t len);
	static bool Seek(int fd, int offset, int whence);
	static bool ReadLine(int fd, char *buf, int len);
	static int CloseFile(int fd);
	static int GetErrorReadWrite(int fd);
	static bool ResolveBundledGameFile(const char *file, char *path, size_t pathSize);
	static char *GetRootDirName() { return ms_rootDirName; }
};

#ifdef __APPLE__
bool ReadGamePathFromINI(char *path, size_t pathSize);
void WriteGamePathToINI(const char *path);
#endif

#endif // __GTA_FILEMGR_H__
