/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io.block.index;

import java.io.IOException;

import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.SkipList;
import net.metanotion.util.skiplist.SkipSpan;

/**
 * I2P version of BSkipSpan
 *
 * BSkipSpan stores all keys and values in-memory, backed by the file.
 * IBSkipSpan stores only the first key, and no values, in-memory.
 *
 * For a get(), here we do a linear search through the span in the file 
 * and load only the found value (super() does a binary search in-memory).
 *
 * For a put() or remove(), we load all keys and values for the span from
 * the file, make the modification, flush() out the keys and values,
 * and null out the keys and values in-memory.
 *
 * Recommended span size is 16.
 *
 * @author zzz
 */
public class IBSkipSpan extends BSkipSpan {

	private Comparable firstKey;
	private static final boolean DEBUG = false;

	@Override
	public SkipSpan newInstance(SkipList sl) {
		if (DEBUG)
			System.err.println("Splitting page " + this.page + " containing " + this.nKeys + '/' + this.spanSize);
		try {
			int newPage = bf.allocPage();
			init(bf, newPage, bf.spanSize);
			SkipSpan rv = new IBSkipSpan(bf, (BSkipList) sl, newPage, keySer, valSer);
			// this is called after a split, so we need the data arrays initialized
			rv.keys = new Comparable[bf.spanSize];
			rv.vals = new Object[bf.spanSize];
			return rv;
		} catch (IOException ioe) { throw new Error(ioe); }
	}

	/**
	 * Flush to disk and null out in-memory keys and values, saving only the first key
	 */
	@Override
	public void flush() {
		super.flush();
		if (nKeys > 0)
			this.firstKey = keys[0];
		else
			this.firstKey = null;
		this.keys = null;
		this.vals = null;
		if (DEBUG)
			System.err.println("Flushed data for page " + this.page + " containing " + this.nKeys + '/' + this.spanSize);
	}

	/**
	 * I2P - second half of load()
	 * Load the whole span's keys and values into memory
	 */
	protected static void loadData(IBSkipSpan bss, BlockFile bf, int spanPage, Serializer key, Serializer val) throws IOException {
		BSkipSpan.loadData(bss, bf, spanPage, key, val);
		if (bss.nKeys > 0)
			bss.firstKey = bss.keys[0];
		if (DEBUG)
			System.err.println("Loaded data for page " + spanPage + " containing " + bss.nKeys + '/' + bss.spanSize + " first key: " + bss.firstKey);
	}

	/**
	 * Must already be seeked to the end of the span header
         * via loadInit() or seekData()
	 */
	private void loadFirstKey() throws IOException {
		if (this.nKeys <= 0)
			return;
		int ksz;
		int curPage = this.page;
		int[] curNextPage = new int[1];
		curNextPage[0] = this.overflowPage;
		int[] pageCounter = new int[1];
		pageCounter[0] = 16;
		ksz = this.bf.file.readShort();
		this.bf.file.skipBytes(2);  //vsz
		pageCounter[0] +=4;
		byte[] k = new byte[ksz];
		curPage = this.bf.readMultiPageData(k, curPage, pageCounter, curNextPage);
		this.firstKey = (Comparable) this.keySer.construct(k);
		if (DEBUG)
			System.err.println("Loaded header for page " + this.page + " containing " + this.nKeys + '/' + this.spanSize + " first key: " + this.firstKey);
	}

	/**
	 * Seek past the span header
	 */
	private void seekData() throws IOException {
		BlockFile.pageSeek(this.bf.file, this.page);
		// 3 ints and 2 shorts
		this.bf.file.skipBytes(16);
	}

	/**
	 * Seek to the start of the span and load the data
	 */
	private void seekAndLoadData() throws IOException {
		seekData();
		loadData(this, this.bf, this.page, this.keySer, this.valSer);
	}

	/**
	 * Linear search through the span in the file for the value.
	 */
	private Object getData(Comparable key) throws IOException {
		seekData();
		int ksz, vsz;
		int curPage = this.page;
		int[] curNextPage = new int[1];
		curNextPage[0] = this.overflowPage;
		int[] pageCounter = new int[1];
		pageCounter[0] = 16;
		//System.out.println("Span Load " + sz + " nKeys " + nKeys + " page " + curPage);
		for(int i=0;i<this.nKeys;i++) {
			if((pageCounter[0] + 4) > BlockFile.PAGESIZE) {
				BlockFile.pageSeek(this.bf.file, curNextPage[0]);
				curPage = curNextPage[0];
				curNextPage[0] = this.bf.file.readInt();
				pageCounter[0] = 4;
			}
			ksz = this.bf.file.readShort();
			vsz = this.bf.file.readShort();
			pageCounter[0] +=4;
			byte[] k = new byte[ksz];
			byte[] v = new byte[vsz];
			curPage = this.bf.readMultiPageData(k, curPage, pageCounter, curNextPage);
			curPage = this.bf.readMultiPageData(v, curPage, pageCounter, curNextPage);
			//System.out.println("i=" + i + ", Page " + curPage + ", offset " + pageCounter[0] + " ksz " + ksz + " vsz " + vsz);
			Comparable ckey = (Comparable) this.keySer.construct(k);
			int diff = ckey.compareTo(key);
			if (diff == 0) {
				//System.err.println("Found " + key + " at " + i + " (first: " + this.firstKey + ')');
				return this.valSer.construct(v);
			}
			if (diff > 0) {
				//System.err.println("NOT Found " + key + " at " + i + " (first: " + this.firstKey + " current: " + ckey + ')');
				return null;
			}
		}
		//System.err.println("NOT Found " + key + " at end (first: " + this.firstKey + ')');
		return null;
	}

	protected IBSkipSpan() { }

	public IBSkipSpan(BlockFile bf, BSkipList bsl, int spanPage, Serializer key, Serializer val) throws IOException {
		if (DEBUG)
			System.err.println("New ibss page " + spanPage);
		BSkipSpan.loadInit(this, bf, bsl, spanPage, key, val);
		loadFirstKey();
		this.next = null;
		this.prev = null;

		IBSkipSpan bss = this;
		IBSkipSpan temp;
		int np = nextPage;
		while(np != 0) {
			temp = (IBSkipSpan) bsl.spanHash.get(new Integer(np));
			if(temp != null) {
				bss.next = temp;
				break;
			}
			bss.next = new IBSkipSpan();
			bss.next.next = null;
			bss.next.prev = bss;
			bss = (IBSkipSpan) bss.next;
			
			BSkipSpan.loadInit(bss, bf, bsl, np, key, val);
			bss.loadFirstKey();
			np = bss.nextPage;
		}

		bss = this;
		np = prevPage;
		while(np != 0) {
			temp = (IBSkipSpan) bsl.spanHash.get(new Integer(np));
			if(temp != null) {
				bss.next = temp;
				break;
			}
			bss.prev = new IBSkipSpan();
			bss.prev.next = bss;
			bss.prev.prev = null;
			bss = (IBSkipSpan) bss.prev;
			
			BSkipSpan.loadInit(bss, bf, bsl, np, key, val);
			bss.loadFirstKey();
			np = bss.prevPage;
		}
	}

	/**
         * Does not call super, we always store first key here
	 */
	@Override
	public Comparable firstKey() {
		return this.firstKey;
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 * This is called only via SkipList.find()
	 */
	@Override
	public SkipSpan getSpan(Comparable key, int[] search) {
		try {
			seekAndLoadData();
		} catch (IOException ioe) {
			throw new Error(ioe);
		}
		SkipSpan rv = super.getSpan(key, search);
		this.keys = null;
		this.vals = null;
		return rv;
	}

	/**
	 * Linear search if in file, Binary search if in memory
	 */
	@Override
	public Object get(Comparable key) {
		try {
			if (nKeys == 0) { return null; }
			if (this.next != null && this.next.firstKey().compareTo(key) <= 0)
				return next.get(key);
			return getData(key);
		} catch (IOException ioe) {
			throw new Error(ioe);
		}
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 */
	@Override
	public SkipSpan put(Comparable key, Object val, SkipList sl)	{
		try {
			seekAndLoadData();
		} catch (IOException ioe) {
			throw new Error(ioe);
		}
		SkipSpan rv = super.put(key, val, sl);
		// flush() nulls out the data
		return rv;
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 */
	@Override
	public Object[] remove(Comparable key, SkipList sl) {
		try {
			seekAndLoadData();
		} catch (IOException ioe) {
			throw new Error(ioe);
		}
		Object[] rv = super.remove(key, sl);
		// flush() nulls out the data
		return rv;
	}
}
