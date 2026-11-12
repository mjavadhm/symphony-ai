#ifndef SYMPHONY_LYRICSWRITER_H
#define SYMPHONY_LYRICSWRITER_H
#include <string>
namespace LyricsWriter {
    // 0 written, 1 existing lyrics, -1 unsupported/invalid, -2 failed.
    int write(const std::string &filename, int fd, const std::string &utf8Lyrics);
}
#endif
