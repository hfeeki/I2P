/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package addressbook;

import java.util.Iterator;
import java.util.List;

/**
 * An iterator over the subscriptions in a SubscriptionList.  Note that this iterator
 * returns AddressBook objects, and not Subscription objects.
 * 
 * @author Ragnarok
 */
public class SubscriptionIterator implements Iterator {

    private Iterator subIterator;
    private String proxyHost;
    private int proxyPort;

    /**
     * Construct a SubscriptionIterator using the Subscriprions in List subscriptions.
     * 
     * @param subscriptions
     *            List of Subscription objects that represent address books.
     * @param proxyHost proxy hostname
     * @param proxyPort proxt port number
     */
    public SubscriptionIterator(List subscriptions, String proxyHost, int proxyPort) {
        this.subIterator = subscriptions.iterator();
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    
    /* (non-Javadoc)
     * @see java.util.Iterator#hasNext()
     */
    public boolean hasNext() {
        return this.subIterator.hasNext();
    }

    /* (non-Javadoc)
     * @see java.util.Iterator#next()
     */
    public Object next() {
        Subscription sub = (Subscription) this.subIterator.next();
        return new AddressBook(sub, this.proxyHost, this.proxyPort);
    }

    /* (non-Javadoc)
     * @see java.util.Iterator#remove()
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }
}