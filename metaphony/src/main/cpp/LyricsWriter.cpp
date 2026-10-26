#include "LyricsWriter.h"
#include "TagLibHelper.h"
#include "tfilestream.h"
#include "tpropertymap.h"
#include "mpegfile.h"
#include "id3v2tag.h"
#include "id3v2header.h"
#include "unsynchronizedlyricsframe.h"
#include "xiphcomment.h"
#include "mp4tag.h"
#include "mp4item.h"
#include "flacfile.h"
#include "mp4file.h"
#include "vorbisfile.h"
#include "opusfile.h"
#include <memory>
#include <unistd.h>

int LyricsWriter::write(const std::string &filename, int fd, const std::string &utf8Lyrics) {
    if (utf8Lyrics.empty() || utf8Lyrics.size() > 1024 * 1024 || utf8Lyrics.find('\0') != std::string::npos) return -2;
    // FileStream owns/closes its descriptor. Keep the caller-owned PFD alive.
    const int ownedFd = dup(fd);
    if (ownedFd < 0) return -2;
    TagLib::FileStream stream(ownedFd, false);
    if (!stream.isOpen()) { close(ownedFd); return -2; }
    if (stream.readOnly()) return -2;
    stream.seek(0);
    const std::unique_ptr<TagLib::File> file(TagLibHelper::detectParser(
        filename.c_str(), &stream, false, TagLib::AudioProperties::Fast));
    if (!file || !file->isValid()) return -1;
    const auto mp3 = dynamic_cast<TagLib::MPEG::File *>(file.get());
    if (!mp3 && !dynamic_cast<TagLib::FLAC::File *>(file.get()) &&
        !dynamic_cast<TagLib::MP4::File *>(file.get()) &&
        !dynamic_cast<TagLib::Ogg::Vorbis::File *>(file.get()) &&
        !dynamic_cast<TagLib::Ogg::Opus::File *>(file.get())) return -1;
    auto properties = file->properties();
    for (auto it = properties.begin(); it != properties.end(); ++it) {
        if (it->first == "LYRICS" || it->first.startsWith("LYRICS:")) {
            for (const auto &value : it->second) if (!value.stripWhiteSpace().isEmpty()) return 1;
        }
    }
    // SYLT is not exposed through TagLib's generic property map. Do not overwrite it.
    if (mp3 && mp3->ID3v2Tag() && !mp3->ID3v2Tag()->frameList("SYLT").isEmpty()) return 1;
    const TagLib::String text(utf8Lyrics, TagLib::String::UTF8);
    // Update only the lyrics field. Reapplying the whole property map can duplicate
    // unrelated COMMENT frames in ID3, and is deliberately avoided here.
    if (mp3) {
        auto tag = mp3->ID3v2Tag(true);
        const auto version = tag->header()->majorVersion() == 3 ? TagLib::ID3v2::v3 : TagLib::ID3v2::v4;
        auto frame = new TagLib::ID3v2::UnsynchronizedLyricsFrame(TagLib::String::UTF8);
        frame->setLanguage("und");
        frame->setText(text);
        tag->addFrame(frame);
        return mp3->save(TagLib::MPEG::File::ID3v2, TagLib::File::StripNone,
                         version, TagLib::File::DoNotDuplicate) ? 0 : -2;
    }
    if (auto flac = dynamic_cast<TagLib::FLAC::File *>(file.get())) flac->xiphComment(true)->addField("LYRICS", text);
    else if (auto mp4 = dynamic_cast<TagLib::MP4::File *>(file.get()))
        mp4->tag()->setItem(TagLib::String("\251lyr", TagLib::String::Latin1), TagLib::MP4::Item(TagLib::StringList(text)));
    else if (auto vorbis = dynamic_cast<TagLib::Ogg::Vorbis::File *>(file.get())) vorbis->tag()->addField("LYRICS", text);
    else if (auto opus = dynamic_cast<TagLib::Ogg::Opus::File *>(file.get())) opus->tag()->addField("LYRICS", text);
    // No tag stripping or audio re-encoding.
    return file->save() ? 0 : -2;
}
