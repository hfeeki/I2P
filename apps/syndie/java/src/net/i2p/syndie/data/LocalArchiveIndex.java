package net.i2p.syndie.data;

import java.util.*;
import net.i2p.data.*;
import net.i2p.syndie.Archive;

/**
 * writable archive index (most are readonly)
 */
public class LocalArchiveIndex extends ArchiveIndex {
    
    public LocalArchiveIndex() {
        super(false);
    }
    
    public void setGeneratedOn(long when) { _generatedOn = when; }
    
    public void setVersion(String v) { _version = v; }
    public void setHeaders(Properties headers) { _headers = headers; }
    public void setHeader(String key, String val) { _headers.setProperty(key, val); }
    public void setAllBlogs(int count) { _allBlogs = count; }
    public void setNewBlogs(int count) { _newBlogs = count; }
    public void setAllEntries(int count) { _allEntries = count; }
    public void setNewEntries(int count) { _newEntries = count; }
    public void setTotalSize(long bytes) { _totalSize = bytes; }
    public void setNewSize(long bytes) { _newSize = bytes; }

    public void addBlog(Hash key, String tag, long lastUpdated) {
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary s = (BlogSummary)_blogs.get(i);
            if ( (s.blog.equals(key)) && (s.tag.equals(tag)) ) {
                s.lastUpdated = Math.max(s.lastUpdated, lastUpdated);
                return;
            }
        }
        BlogSummary summary = new ArchiveIndex.BlogSummary();
        summary.blog = key;
        summary.tag = tag;
        summary.lastUpdated = lastUpdated;
        _blogs.add(summary);
    }
    
    public void addBlogEntry(Hash key, String tag, String entry) {
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary summary = (BlogSummary)_blogs.get(i);
            if (summary.blog.equals(key) && (summary.tag.equals(tag)) ) {
                long entryId = Archive.getEntryIdFromIndexName(entry);
                int kb = Archive.getSizeFromIndexName(entry);
                System.out.println("Adding entry " + entryId + ", size=" + kb + "KB [" + entry + "]");
                EntrySummary entrySummary = new EntrySummary(new BlogURI(key, entryId), kb);
                for (int j = 0; j < summary.entries.size(); j++) {
                    EntrySummary cur = (EntrySummary)summary.entries.get(j);
                    if (cur.entry.equals(entrySummary.entry))
                        return;
                }
                summary.entries.add(entrySummary);
                return;
            }
        }
    }
    
    public void addNewestBlog(Hash key) { 
        if (!_newestBlogs.contains(key))
            _newestBlogs.add(key); 
    }
    public void addNewestEntry(BlogURI entry) { 
        if (!_newestEntries.contains(entry))
            _newestEntries.add(entry); 
    }

    public void addReply(BlogURI parent, BlogURI reply) {
        Set replies = (Set)_replies.get(parent);
        if (replies == null) {
            replies = Collections.synchronizedSet(new TreeSet(BlogURIComparator.HIGHEST_ID_FIRST));
            _replies.put(parent, replies);
        }
        replies.add(reply);
        //System.err.println("Adding reply to " + parent + " from child " + reply + " (# replies: " + replies.size() + ")");
    }

    private static class BlogURIComparator implements Comparator {
        public static final BlogURIComparator HIGHEST_ID_FIRST = new BlogURIComparator(true);
        public static final BlogURIComparator HIGHEST_ID_LAST = new BlogURIComparator(false);
        private boolean _highestFirst;
        public BlogURIComparator(boolean highestFirst) {
            _highestFirst = highestFirst;
        }
        
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || !(lhs instanceof BlogURI) ) return 1;
            if ( (rhs == null) || !(rhs instanceof BlogURI) ) return -1;
            BlogURI l = (BlogURI)lhs;
            BlogURI r = (BlogURI)rhs;
            if (l.getEntryId() > r.getEntryId())
                return (_highestFirst ? 1 : -1);
            else if (l.getEntryId() < r.getEntryId())
                return (_highestFirst ? -1 : 1);
            else
                return DataHelper.compareTo(l.getKeyHash().getData(), r.getKeyHash().getData());
        }
    }
}
