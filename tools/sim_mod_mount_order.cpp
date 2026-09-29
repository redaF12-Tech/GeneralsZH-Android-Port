// GeneralsX @bugfix Android port 29/09/2026 Behavioral simulation of the two
// branches' native Mod loading mount sequences, to isolate what changed
// NMM -> full-app-28-9. Replicates the REAL insertion and lookup logic from
// ArchiveFileSystem.cpp (sortedByName/overwrite/append insert; get_range +
// FIRST-instance lookup from STLUtils.h) against the retail / base-generals /
// community-patch / mod load order of each branch.
//
// Build & run:
//   g++ -std=c++17 -O1 -o /tmp/sim_mod_mount \
//     scripts/tooling/cpp/sim_mod_mount_order.cpp && /tmp/sim_mod_mount
//
// (Tooling-only diagnostic; not wired into any build system.)

#include <map>
#include <string>
#include <vector>
#include <cstdio>
#include <algorithm>
#include <cstring>

// ---------------------------------------------------------------------------
// Minimal AsciiString stand-in matching the engine's case-folding behavior.
// ---------------------------------------------------------------------------
struct AStr {
    std::string s;
    AStr() {}
    AStr(const char* c) : s(c) {}
    AStr(const std::string& c) : s(c) {}
    void toLower() { for (size_t i = 0; i < s.size(); ++i) if (s[i] >= 'A' && s[i] <= 'Z') s[i] = char(s[i] - 'A' + 'a'); }
    const char* str() const { return s.c_str(); }
    bool empty() const { return s.empty(); }
    bool operator<(const AStr& o) const { return s < o.s; }
    bool operator==(const AStr& o) const { return s == o.s; }
    static AStr lower(const AStr& x) { AStr r(x); r.toLower(); return r; }
    int compareNoCase(const AStr& o) const {
        const AStr a = lower(*this), b = lower(o);
        return a.s.compare(b.s);
    }
};

// getBaseFilename: split on '\' or '/', return the last component (case kept).
static AStr getBaseFilename(const AStr& path)
{
    const char* str = path.str();
    const char* p1 = strrchr(str, '\\');
    const char* p2 = strrchr(str, '/');
    const char* sep = (p1 == nullptr) ? p2 : ((p2 == nullptr) ? p1 : ((p1 > p2) ? p1 : p2));
    return sep ? AStr(sep + 1) : path;
}

// ---------------------------------------------------------------------------
// Archive stand-in: archive name (as passed to openArchiveFile, case kept) and
// its internal file list (engine-style backslash paths).
// ---------------------------------------------------------------------------
struct ArchiveFile {
    std::string name;              // e.g. "Data/INI/INI.big" (mounted path)
    std::vector<std::string> files; // internal entries, e.g. "Data\INI\Game.ini"
};

struct FileSystem {
    struct DirInfo {
        std::map<AStr, DirInfo> m_directories;
        std::multimap<AStr, ArchiveFile*> m_files; // key = lowercased file token
    };

    DirInfo m_rootDirectory;
    std::multimap<AStr, ArchiveFile*> m_archiveFileMap; // key = lowercased archive path

    void loadIntoDirectoryTree(ArchiveFile* archiveFile, bool overwrite, bool sortedByName)
    {
        for (size_t idx = 0; idx < archiveFile->files.size(); ++idx)
        {
            DirInfo* dirInfo = &m_rootDirectory;

            std::string tokenizer = archiveFile->files[idx];
            for (size_t i = 0; i < tokenizer.size(); ++i)
                if (tokenizer[i] >= 'A' && tokenizer[i] <= 'Z') tokenizer[i] = char(tokenizer[i] - 'A' + 'a');

            // Tokenize on '\/'; the engine's "while (infoInPath && (!token.find('.') || tokenizer.find('.')))"
            // loop treats the LAST token (which carries a '.') as the file name.
            std::vector<std::string> tokens;
            std::string cur;
            for (size_t i = 0; i < tokenizer.size(); ++i) {
                if (tokenizer[i] == '\\' || tokenizer[i] == '/') { tokens.push_back(cur); cur.clear(); }
                else cur += tokenizer[i];
            }
            if (!cur.empty()) tokens.push_back(cur);
            if (tokens.empty()) continue;

            AStr token(tokens.back().c_str()); // the file name (lowercased)
            for (size_t t = 0; t + 1 < tokens.size(); ++t) { // directories
                AStr d(tokens[t].c_str());
                std::map<AStr, DirInfo>::iterator dit = dirInfo->m_directories.find(d);
                if (dit == dirInfo->m_directories.end())
                    dirInfo = &(dirInfo->m_directories[d]);
                else
                    dirInfo = &dit->second;
            }

            std::multimap<AStr, ArchiveFile*>::iterator fileIt;
            if (sortedByName)
            {
                const AStr baseName = getBaseFilename(AStr(archiveFile->name.c_str()));
                std::pair<std::multimap<AStr, ArchiveFile*>::iterator, std::multimap<AStr, ArchiveFile*>::iterator>
                    range = dirInfo->m_files.equal_range(token);
                fileIt = range.first;
                while (fileIt != range.second) {
                    AStr existingBase = getBaseFilename(AStr(fileIt->second->name.c_str()));
                    if (existingBase.compareNoCase(baseName) <= 0) ++fileIt; else break;
                }
            }
            else if (overwrite)
            {
                fileIt = dirInfo->m_files.find(token);
            }
            else
            {
                fileIt = dirInfo->m_files.end();
            }

            dirInfo->m_files.insert(fileIt, std::make_pair(token, archiveFile));
        }

        m_archiveFileMap.insert(std::make_pair(AStr(archiveFile->name.c_str()), archiveFile));
    }

    // FIRST-instance lookup: equal_range(...).first, like get_range(map, key, 0).
    const char* lookup(const char* path)
    {
        DirInfo* dirInfo = &m_rootDirectory;
        std::string tokenizer = path;
        for (size_t i = 0; i < tokenizer.size(); ++i)
            if (tokenizer[i] >= 'A' && tokenizer[i] <= 'Z') tokenizer[i] = char(tokenizer[i] - 'A' + 'a');

        std::vector<std::string> tokens;
        std::string cur;
        for (size_t i = 0; i < tokenizer.size(); ++i) {
            if (tokenizer[i] == '\\' || tokenizer[i] == '/') { tokens.push_back(cur); cur.clear(); }
            else cur += tokenizer[i];
        }
        if (!cur.empty()) tokens.push_back(cur);
        if (tokens.empty()) return "<none>";

        AStr token(tokens.back().c_str());
        for (size_t t = 0; t + 1 < tokens.size(); ++t) {
            std::map<AStr, DirInfo>::iterator dit = dirInfo->m_directories.find(AStr(tokens[t].c_str()));
            if (dit == dirInfo->m_directories.end()) return "<missing-dir>";
            dirInfo = &dit->second;
        }

        std::pair<std::multimap<AStr, ArchiveFile*>::iterator, std::multimap<AStr, ArchiveFile*>::iterator>
            range = dirInfo->m_files.equal_range(token);
        if (range.first == range.second) return "<missing-file>";
        return range.first->second->name.c_str();
    }

    void dump(const char* label, const char* dir, const char* file)
    {
        std::string p = dir;
        p += "\\";
        p += file;
        printf("    [%s] %s -> %s\n", label, p.c_str(), lookup(p.c_str()));
    }
};

// Mount helpers mirroring each branch's loadMods() + init().
static void mountRetail(FileSystem* fs)
{
    // Retail ZH + base Generals, alphabetical within each group (init()).
    // Shared key contested: Data\INI\Game.ini exists in both INI.big and INIZH.big;
    // INI.big (base) mounts first, INIZH.big's overwrite insert lands BEFORE it.
    ArchiveFile* ini = new ArchiveFile(); ini->name = "Data/INI/INI.big";
    ini->files.push_back("Data\\INI\\Game.ini");       // shared
    ini->files.push_back("Data\\INI\\BaseOnly.ini");
    fs->loadIntoDirectoryTree(ini, true, false);

    ArchiveFile* inizh = new ArchiveFile(); inizh->name = "Data/INI/INIZH.big";
    inizh->files.push_back("Data\\INI\\Game.ini");     // shared
    inizh->files.push_back("Data\\INI\\ZHOnly.ini");
    fs->loadIntoDirectoryTree(inizh, true, false);
}

static void mountPatch(FileSystem* fs)
{
    // 500_900_CommunityPatch_CoreINI.big, sortedByName=TRUE (full-app-28-9 only).
    ArchiveFile* patch = new ArchiveFile(); patch->name = "<userdata>/GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big";
    patch->files.push_back("Data\\INI\\Game.ini");     // same key as retail
    patch->files.push_back("Data\\INI\\PatchOnly.ini");
    fs->loadIntoDirectoryTree(patch, false, true);
}

static void mountModDir(FileSystem* fs)
{
    // GENERALSX_MOD_PATH / m_modDir scan, overwrite=TRUE, per loadBigFilesFromDirectory.
    ArchiveFile* mod = new ArchiveFile(); mod->name = "<moddir>/MyMod.big";
    mod->files.push_back("Data\\INI\\Game.ini");       // contested with patch + retail
    mod->files.push_back("Data\\INI\\ModOnly.ini");
    fs->loadIntoDirectoryTree(mod, true, false);
}

static void runBranch(const char* label, bool hasPatch)
{
    printf("%s:\n", label);
    FileSystem fs;
    mountRetail(&fs);                    // init() - both branches
    if (hasPatch) mountPatch(&fs);       // full-app-28-9 only
    mountModDir(&fs);                    // loadMods() - both branches (env bridge; NMM first, FA29 last)
    fs.dump(label, "Data\\INI", "Game.ini");
    fs.dump(label, "Data\\INI", "BaseOnly.ini");
    fs.dump(label, "Data\\INI", "ZHOnly.ini");
    fs.dump(label, "Data\\INI", "PatchOnly.ini");
    fs.dump(label, "Data\\INI", "ModOnly.ini");
}

int main()
{
    runBranch("NMM   (retail -> [no patch] -> mod)", false);
    runBranch("FA29  (retail -> patch -> mod)", true);
    return 0;
}
