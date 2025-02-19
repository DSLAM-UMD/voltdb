/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package org.voltdb.utils;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.DBBPool.BBContainer;
import org.voltcore.utils.DeferredSerialization;
import org.voltcore.utils.Pair;
import org.voltdb.MockVoltDB;
import org.voltdb.VoltDB;
import org.voltdb.VoltType;
import org.voltdb.export.ExportDataSource.StreamTableSchemaSerializer;
import org.voltdb.utils.BinaryDeque.BinaryDequeTruncator;
import org.voltdb.utils.BinaryDeque.TruncatorResponse;

import com.google_voltpatches.common.collect.Sets;

public class TestPersistentBinaryDeque {

    public final static File TEST_DIR = new File("/tmp/" + System.getProperty("user.name"));
    public static final String TEST_NONCE = "pbd_nonce";
    private static final String CURSOR_ID = "testPBD";
    private final static VoltLogger logger = new VoltLogger("EXPORT");

    private static ByteBuffer defaultBuffer() {
        return getFilledBuffer(42);
    }

    private static BBContainer defaultContainer() {
        return DBBPool.wrapBB(defaultBuffer());
    }

    private PersistentBinaryDeque m_pbd;
    private MockVoltDB m_mockVoltDB;
    StreamTableSchemaSerializer m_ds;

    @Before
    public void setUp() throws Exception {
        setupTestDir();
        m_mockVoltDB = new MockVoltDB();
        m_mockVoltDB.addTable("TableName", false);
        m_mockVoltDB.addColumnToTable("TableName", "COL1", VoltType.INTEGER, false, null, VoltType.INTEGER);
        m_mockVoltDB.addColumnToTable("TableName", "COL2", VoltType.STRING, false, null, VoltType.STRING);
        VoltDB.replaceVoltDBInstanceForTest(m_mockVoltDB);
        m_ds = new StreamTableSchemaSerializer(
                VoltDB.instance().getCatalogContext(), "TableName");
        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger, true);
    }

    public static void setupTestDir() throws IOException {
        if (TEST_DIR.exists()) {
            for (File f : TEST_DIR.listFiles()) {
                VoltFile.recursivelyDelete(f);
            }
            TEST_DIR.delete();
        }
        TEST_DIR.mkdir();
    }

    @After
    public void tearDown() throws Exception {
        try {
            m_mockVoltDB.shutdown(null);
            m_pbd.close();
        } catch (Exception e) {}
        try {
            tearDownTestDir();
        } finally {
            m_pbd = null;
            m_ds = null;
        }
        System.gc();
        System.runFinalization();
    }

    public static void tearDownTestDir() {
        if (TEST_DIR.exists()) {
            for (File f : TEST_DIR.listFiles()) {
                f.delete();
            }
            TEST_DIR.delete();
        }
    }

    public static ByteBuffer getFilledBuffer(long fillValue) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024 * 2);
        Random r = new Random(42);
        while (buf.remaining() > 15) {
            buf.putLong(fillValue);
            buf.putLong(r.nextLong());
        }
        buf.clear();
        return buf;
    }

    private static ByteBuffer getFilledSmallBuffer(long fillValue) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1024);
        while (buf.remaining() > 15) {
            buf.putLong(fillValue);
            buf.putLong(fillValue);
        }
        buf.clear();
        return buf;
    }

    /**
     * Return a "sorted" directory listing.
     *
     * Note: since the nonce is not passed in to filter out the output, this should only be
     * used for tests that use only 1 nonce.
     *
     * Since the files are not guaranteed to be sorted by name, decode the file sequence.
     *
     * @return
     * @throws IOException
     */
    public static List<File> getSortedDirectoryListing() throws IOException {
        return getSortedDirectoryListing(false);
    }

    public static List<File>  getSortedDirectoryListing(boolean isPBDClosed) throws IOException {

        HashMap<Long, File> filesById = new HashMap<>();
        PairSequencer<Long> sequencer = new PairSequencer<>();

        for (File f : TEST_DIR.listFiles()) {
            String fname = f.getName();
            if (f.length()  ==0) {
                System.out.println("Ignoring empty file " + fname);
                continue;
            }
            String rootname = fname.substring(0, fname.lastIndexOf("."));
            String[] parts = rootname.split("_");
            if (parts.length < 3) {
                System.out.println("Ignoring file " + fname);
                continue;
            }

            // Parse the counters
            long prevCnt = 0L;
            long curCnt = 0L;
            try {
                prevCnt = Long.parseLong(parts[parts.length - 1]);
                curCnt = Long.parseLong(parts[parts.length - 2]);
            } catch (NumberFormatException ex) {
                throw new IOException("Failed to parse counters in " + fname + ":" + ex);
            }

            filesById.put(curCnt, f);
            sequencer.add(new Pair<Long, Long>(prevCnt, curCnt));
        }

        // Deduce the sequence from the extracted segment ids
        Deque<Deque<Long>> sequences = sequencer.getSequences();
        if (sequences.size() > 1) {
            throw new IOException("Found " + sequences.size() + " PBD sequences");
        }
        Deque<Long> sequence = sequences.getFirst();

        LinkedList<File> sorted = new LinkedList<>();
        for (Long segmentId : sequence) {
            File file = filesById.get(segmentId);
            if (file == null) {
                // This is an Instant in the sequence referring to a previous file that
                // was deleted, so move on.
                continue;
            }
            sorted.addLast(file);
        }

        // Verify the PBD segment finalization
        File lastEntry = sorted.peekLast() != null ? sorted.removeLast() : null;
        if (lastEntry != null) {
            if (isPBDClosed) {
                // When PBD is closed, last entry SHOULD be final
                assertTrue(PBDSegment.isFinal(lastEntry));
            }
            else {
                // When PBD is open, last entry SHOULD NOT be final
                assertFalse(PBDSegment.isFinal(lastEntry));
            }
        }
        File penultimate = sorted.peekLast() != null ? sorted.removeLast() : null;
        if (penultimate != null) {
            if (isPBDClosed) {
                // When PBD is closed, penultimate entry SHOULD be final
                assertTrue(PBDSegment.isFinal(penultimate));
            }
            else {
                // When PBD is open, penultimateEntry entry MAY be final or not, depending on recovery scenario
                // FIXME: we could test this
                if (!PBDSegment.isFinal(penultimate)) {
                    System.out.println("Penultimate segment not final: " + penultimate.getName());
                }
            }
        }
        for (File other : sorted) {
            if (!PBDSegment.isFinal(other)) {
                System.out.println("Every segment except last and optionally penultimate should be final: "
                        + other.getName());
            }
        }

        if (penultimate != null) {
            sorted.addLast(penultimate);
        }
        if (lastEntry != null) {
            sorted.addLast(lastEntry);
        }
        return sorted;
    }

    @Test
    public void testTruncateFirstElement() throws Exception {
        System.out.println("Running testTruncateFirstElement");
        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);

        for (int ii = 0; ii < 150; ii++) {
            m_pbd.offer( DBBPool.wrapBB(getFilledBuffer(ii)) );
        }

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 4);

        m_pbd.close();

        // Get directory listing with PBD closed
        listing = getSortedDirectoryListing(true);
        assertEquals(listing.size(), 4);

        m_pbd = new PersistentBinaryDeque( TEST_NONCE, m_ds, TEST_DIR, logger );

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 5);

        m_pbd.parseAndTruncate(new BinaryDequeTruncator() {
            @Override
            public TruncatorResponse parse(BBContainer bbc) {
                return PersistentBinaryDeque.fullTruncateResponse();
            }

        });

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));
    }

    @Test
    public void testCloseEmptyShouldNotDelete() throws Exception {
        System.out.println("Running testCloseEmptyShouldNotDelete");
        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);
        m_pbd.close();

        // Test directlry listing on closed PBD
        listing = getSortedDirectoryListing(true);
        assertEquals(listing.size(), 1);

        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger, true);

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);

        m_pbd.parseAndTruncate(new BinaryDequeTruncator() {
            @Override
            public TruncatorResponse parse(BBContainer bbc) {
                fail();
                return null;
            }

        });

        for (int ii = 0; ii < 96; ii++) {
            m_pbd.offer( DBBPool.wrapBB(getFilledBuffer(ii)) );
        }

        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        for (long ii = 0; ii < 96; ii++) {
            BBContainer cont = reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY);
            try {
                assertNotNull(cont);
                assertEquals(cont.b().remaining(), 1024 * 1024 * 2);
                while (cont.b().remaining() > 15) {
                    assertEquals(ii, cont.b().getLong());
                    cont.b().getLong();
                }
            } catch (Throwable t) {
                System.err.println("Something threw");
                t.printStackTrace();
                throw t;
            } finally {
                cont.discard();
            }
        }
    }

    @Test
    public void testTruncatorWithFullTruncateReturn() throws Exception {
        System.out.println("Running testTruncatorWithFullTruncateReturn");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        for (int ii = 0; ii < 150; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }

        m_pbd.close();

        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger, true);

        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 5);

        m_pbd.parseAndTruncate(new BinaryDequeTruncator() {
            private long m_objectsParsed = 0;
            @Override
            public TruncatorResponse parse(BBContainer bbc) {
                ByteBuffer b = bbc.b();
                if (b.getLong(0) != m_objectsParsed) {
                    System.out.println("asd");
                }
                assertEquals(m_objectsParsed, b.getLong(0));
                assertEquals(1024 * 1024 * 2, b.remaining());
                if (b.getLong(0) == 45) {
                    b.limit(b.remaining() / 2);
                    return PersistentBinaryDeque.fullTruncateResponse();
                }
                while (b.remaining() > 15) {
                    assertEquals(b.getLong(), m_objectsParsed);
                    b.getLong();
                }
                m_objectsParsed++;
                return null;
            }

        });

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 2);

        for (int ii = 46; ii < 96; ii++) {
            // Note: new segment after truncate?
            if (ii == 46) {
                m_pbd.updateExtraHeader(m_ds);
            }
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }

        reader = m_pbd.openForRead(CURSOR_ID);
        long actualSizeInBytes = 0;
        long reportedSizeInBytes = reader.sizeInBytes();
        long blocksFound = 0;
        BBContainer cont = null;

        while ((cont = pollOnceWithoutDiscard(reader)) != null) {
            try {
                ByteBuffer buffer = cont.b();
                if (blocksFound == 45) {
                    blocksFound++;//white lie, so we expect the right block contents
                }
                assertEquals(buffer.remaining(), 1024 * 1024 * 2);
                actualSizeInBytes += buffer.remaining();
                while (buffer.remaining() > 15) {
                    assertEquals(buffer.getLong(), blocksFound);
                    buffer.getLong();
                }
            } finally {
                blocksFound++;
                cont.discard();
            }
        }
        assertEquals(actualSizeInBytes, reportedSizeInBytes);
        assertEquals(blocksFound, 96);
    }

    @Test
    public void testTruncator() throws Exception {
        System.out.println("Running testTruncator");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        for (int ii = 0; ii < 160; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }

        m_pbd.close();

        m_pbd = new PersistentBinaryDeque( TEST_NONCE, m_ds, TEST_DIR, logger );

        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 5);

        m_pbd.parseAndTruncate(new BinaryDequeTruncator() {
            private long m_objectsParsed = 0;
            @Override
            public TruncatorResponse parse(BBContainer bbc) {
                ByteBuffer b = bbc.b();
                assertEquals(m_objectsParsed, b.getLong(0));
                assertEquals(1024 * 1024 * 2, b.remaining());
                if (b.getLong(0) == 45) {
                    b.limit(b.remaining() / 2);
                    return new PersistentBinaryDeque.ByteBufferTruncatorResponse(b.slice());
                }
                while (b.remaining() > 15) {
                    assertEquals(b.getLong(), m_objectsParsed);
                    b.getLong();
                }
                m_objectsParsed++;
                return null;
            }

        });
        reader = m_pbd.openForRead(CURSOR_ID);
        assertEquals(95420416, reader.sizeInBytes());

        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 2);

        for (int ii = 46; ii < 96; ii++) {
            // Note: new segment after truncate?
            if (ii == 46) {
                m_pbd.updateExtraHeader(m_ds);
            }
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }

        long actualSizeInBytes = 0;
        long reportedSizeInBytes = reader.sizeInBytes();
        long blocksFound = 0;
        BBContainer cont = null;
        while ((cont = pollOnceWithoutDiscard(reader)) != null) {
            try {
                ByteBuffer buffer = cont.b();
                if (blocksFound == 45) {
                    assertEquals(buffer.remaining(), 1024 * 1024);
                } else {
                    assertEquals(buffer.remaining(), 1024 * 1024 * 2);
                }
                actualSizeInBytes += buffer.remaining();
                while (buffer.remaining() > 15) {
                    assertEquals(buffer.getLong(), blocksFound);
                    buffer.getLong();
                }
            } finally {
                blocksFound++;
                cont.discard();
            }
        }
        assertEquals(actualSizeInBytes, reportedSizeInBytes);
        assertEquals(blocksFound, 96);
    }

    @Test
    public void testReaderIsEmpty() throws Exception {
        System.out.println("Running testReaderIsEmpty");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertTrue(reader.isEmpty());
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));
        assertTrue(reader.isEmpty());

        m_pbd.offer(defaultContainer());
        assertFalse(reader.isEmpty());
        pollOnce(reader);
        assertTrue(reader.isEmpty());

        // more than one segment
        for (int i = 0; i < 50; i++) {
            m_pbd.offer(defaultContainer());
        }
        assertFalse(reader.isEmpty());
        for (int i = 0; i < 50; i++) {
            pollOnce(reader);
            if (i<49) {
                assertFalse(reader.isEmpty());
            } else {
                assertTrue(reader.isEmpty());
            }
        }
    }

    @Test
    public void testReaderNumObjects() throws Exception {
        System.out.println("Running testReaderNumObjects");
        String cursor1 = "testPBD1";
        BinaryDequeReader reader1 = m_pbd.openForRead(cursor1);
        int count = 0;
        int totalAdded = 0;
        assertEquals(count, reader1.getNumObjects());
        assertNull(reader1.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));
        assertEquals(count, reader1.getNumObjects());

        count++;
        m_pbd.offer(defaultContainer());
        totalAdded++;
        assertEquals(count, reader1.getNumObjects());

        // a second reader
        String cursor2 = "testPBD2";
        BinaryDequeReader reader2 = m_pbd.openForRead(cursor2);

        pollOnce(reader1);
        assertEquals(count-1, reader1.getNumObjects());
        assertEquals(count, reader2.getNumObjects());
        pollOnce(reader2);
        count--;
        assertEquals(count, reader1.getNumObjects());
        assertEquals(count, reader2.getNumObjects());

        // offer segments
        for (int i = 0; i < 50; i++) {
            m_pbd.offer(defaultContainer());
            totalAdded++;
            count++;
            assertEquals(count, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
        }

        for (int i = 0; i < 50; i++) {
            pollOnce(reader1);
            assertEquals(count-1, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
            pollOnce(reader2);
            count--;
            assertEquals(count, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
        }

        final int segmentfullCount = 47;
        // start a 3rd reader after segments have been deleted
        String cursor3 = "testPBD3";
        BinaryDequeReader lateReader = m_pbd.openForRead(cursor3);
        int toAddForLate = totalAdded%segmentfullCount;
        assertEquals(count+toAddForLate, lateReader.getNumObjects());

        // offer segments with all 3 readers
        for (int i = 0; i < 50; i++) {
            m_pbd.offer(defaultContainer());
            count++;
            assertEquals(count, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
            assertEquals(count+toAddForLate, lateReader.getNumObjects());
        }

        for (int i = 0; i < 50; i++) {
            pollOnce(reader1);
            assertEquals(count-1, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
            assertEquals(count+toAddForLate, lateReader.getNumObjects());
            pollOnce(reader2);
            assertEquals(count-1, reader1.getNumObjects());
            assertEquals(count-1, reader2.getNumObjects());
            assertEquals(count+toAddForLate, lateReader.getNumObjects());
            pollOnce(lateReader);
            count--;
            assertEquals(count, reader1.getNumObjects());
            assertEquals(count, reader2.getNumObjects());
            assertEquals(count+toAddForLate, lateReader.getNumObjects());
        }

        assert(count==0);
        for (int i=0; i < toAddForLate; i++) {
            pollOnce(lateReader);
            assertEquals(toAddForLate-i-1, lateReader.getNumObjects());
        }

        assertEquals(0, reader1.getNumObjects());
        assertEquals(0, reader2.getNumObjects());
        assertEquals(0, lateReader.getNumObjects());
    }

    @Test
    public void testOfferThenPoll() throws Exception {
        System.out.println("Running testOfferThenPoll");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        //Make sure a single file with the appropriate data is created
        m_pbd.offer(defaultContainer());
        File files[] = TEST_DIR.listFiles();
        assertEquals( 1, files.length);
        assertTrue("pbd_nonce_0000000001_0000000002.pbd".equals(files[0].getName()));

        //Now make sure the current write file is stolen and a new write file created
        pollOnce(reader);
    }

    @Test
    public void testCloseOldSegments() throws Exception {
        System.out.println("Running testCloseOldSegments");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        final int total = 100;

        //Make sure several files with the appropriate data is created
        for (int i = 0; i < total; i++) {
            m_pbd.offer(defaultContainer());
        }
        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 3);

        Set<String> actualFiles = Sets.newHashSet();
        for (File f : listing) {
            actualFiles.add(f.getName());
        }
        Set<String> expectedFiles = Sets.newHashSet();
        expectedFiles.add("pbd_nonce_0000000001_0000000002.pbd");
        expectedFiles.add("pbd_nonce_0000000003_0000000001.pbd");
        expectedFiles.add("pbd_nonce_0000000004_0000000003.pbd");
        assertEquals(expectedFiles, actualFiles);

        //Now make sure the current write file is stolen and a new write file created
        for (int i = 0; i < total; i++) {
            pollOnce(reader);
        }
        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);
    }

    @Test
    public void testDontCloseReadSegment() throws Exception {
        System.out.println("Running testDontCloseReadSegment");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        final int total = 100;

        //Make sure a single file with the appropriate data is created
        for (int i = 0; i < 5; i++) {
            m_pbd.offer(defaultContainer());
        }
        assertEquals(1, TEST_DIR.listFiles().length);

        // Read one buffer from the segment so that it's considered being polled from.
        pollOnce(reader);

        for (int i = 5; i < total; i++) {
            m_pbd.offer(defaultContainer());
        }
        List<File> listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 3);

        Set<String> actualFiles = Sets.newHashSet();
        for (File f : listing) {
            actualFiles.add(f.getName());
        }
        Set<String> expectedFiles = Sets.newHashSet();
        expectedFiles.add("pbd_nonce_0000000001_0000000002.pbd");
        expectedFiles.add("pbd_nonce_0000000003_0000000001.pbd");
        expectedFiles.add("pbd_nonce_0000000004_0000000003.pbd");
        assertEquals(expectedFiles, actualFiles);

        //Now make sure the current write file is stolen and a new write file created
        for (int i = 1; i < total; i++) {
            pollOnce(reader);
        }
        listing = getSortedDirectoryListing();
        assertEquals(listing.size(), 1);
    }

    @Test
    public void testOfferThenPushThenPoll() throws Exception {
        System.out.println("Running testOfferThenPushThenPoll");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertTrue(reader.isEmpty());
        //Make it create two full segments
        for (int ii = 0; ii < 96; ii++) {
            m_pbd.offer(defaultContainer());
            assertFalse(reader.isEmpty());
        }
        File files[] = TEST_DIR.listFiles();
        assertEquals( 3, files.length);

        //Now create two buffers with different data to push at the front
        final ByteBuffer buffer1 = getFilledBuffer(16);
        final ByteBuffer buffer2 = getFilledBuffer(32);
        BBContainer pushContainers[] = new BBContainer[2];
        pushContainers[0] = DBBPool.dummyWrapBB(buffer1);
        pushContainers[1] = DBBPool.dummyWrapBB(buffer2);

        m_pbd.push(pushContainers, m_ds);

        //Expect this to create a single new file
        List<File> listing = getSortedDirectoryListing();
        assertEquals( 4, listing.size());

        // Check the expected ordering of the PBDs
        File f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000002_0000000005.pbd", f0.getName());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000001_0000000002.pbd", f0.getName());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000003_0000000001.pbd", f0.getName());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000004_0000000003.pbd", f0.getName());

        //Poll the two at the front and check that the contents are what is expected
        buffer1.clear();
        pollOnceAndVerify(reader, buffer1);
        buffer2.clear();
        pollOnceAndVerify(reader, buffer2);

        listing = getSortedDirectoryListing();
        assertEquals( 3, listing.size());

        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000001_0000000002.pbd", f0.getName());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000003_0000000001.pbd", f0.getName());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000004_0000000003.pbd", f0.getName());

        ByteBuffer defaultBuffer = defaultBuffer();
        //Now poll the rest and make sure the data is correct
        for (int ii = 0; ii < 96; ii++) {
            defaultBuffer.clear();
            pollOnceAndVerify(reader, defaultBuffer);
        }

        assertTrue(reader.isEmpty());

        //Expect just the current write segment
        listing = getSortedDirectoryListing();
        assertEquals( 1, listing.size());
        f0 = listing.remove(0);
        assertEquals("pbd_nonce_0000000004_0000000003.pbd", f0.getName());
    }

    @Test
    public void testOfferCloseThenReopen() throws Exception {
        System.out.println("Running testOfferCloseThenReopen");
        //Make it create two full segments
        for (int ii = 0; ii < 96; ii++) {
            m_pbd.offer(defaultContainer());
        }
        File files[] = TEST_DIR.listFiles();
        assertEquals( 3, files.length);

        m_pbd.sync();
        m_pbd.close();

        m_pbd = new PersistentBinaryDeque( TEST_NONCE, m_ds, TEST_DIR, logger );
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);

        ByteBuffer defaultBuffer = defaultBuffer();
        //Now poll all of it and make sure the data is correct
        for (int ii = 0; ii < 96; ii++) {
            defaultBuffer.clear();
            pollOnceAndVerify(reader, defaultBuffer);
        }

        //Expect just the current write segment
        List<File> listing = getSortedDirectoryListing();
        assertEquals( 1, listing.size());
    }

    @Test
    public void testInvalidDirectory() throws Exception {
        System.out.println("Running testInvalidDirectory");
        m_pbd.close();
        try {
            m_pbd = new PersistentBinaryDeque( "foo", m_ds, new File("/usr/bin"), logger);
        } catch (IOException e) {
            return;
        }
        fail();
    }

    @Test
    public void testMissingSegment() throws Exception {
        System.out.println("Running testMissingSegment");
        for (int ii = 0; ii < 256; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(64)) );
        }
        m_pbd.close();

        List<File> listing = getSortedDirectoryListing(true);
        assertEquals(listing.size(), 6);

        // Delete the fourth file
        File toDelete = null;
        for (int i = 0; i < 4; i++) {
            toDelete = listing.remove(0);
        }
        assertTrue(toDelete.exists());
        assertTrue(toDelete.delete());
        try {
            m_pbd = new PersistentBinaryDeque( TEST_NONCE, m_ds, TEST_DIR, logger );
        } catch (IOException e) {
            return;
        }
        fail();
    }

    @Test
    public void testOfferFailsWhenClosed() throws Exception {
        System.out.println("Running testOfferFailsWhenClosed");
        m_pbd.close();
        BBContainer cont = DBBPool.wrapBB(ByteBuffer.allocate(20));
        try {
            m_pbd.offer( cont );
        } catch (IOException e) {
            return;
        } finally {
            cont.discard();
        }
        fail();
    }

    @Test
    public void testPushFailsWhenClosed() throws Exception {
        System.out.println("Running testPushFailsWhenClosed");
        m_pbd.close();
        BBContainer objs[] = new BBContainer[] { DBBPool.wrapBB(ByteBuffer.allocate(20)) };
        try {
            m_pbd.push(objs, m_ds);
        } catch (IOException e) {
            return;
        } finally {
            objs[0].discard();
        }
        fail();
    }

    @Test
    public void testPushMultipleSegments() throws Exception {
        System.out.println("Running testPushMultipleSegments");
        m_pbd.push(new BBContainer[] {
                DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32)) ,
                DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32)) ,
                DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32)) },
                m_ds);
    }

    @Test
    public void testPollWhileClosed() throws Exception {
        System.out.println("Running testPollWhileClosed");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        m_pbd.close();
        try {
            reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY);
        } catch (IOException e) {
            return;
        }
        fail();
    }

    @Test
    public void testSyncWhileClosed() throws Exception {
        System.out.println("Running testSyncWhileClosed");
        m_pbd.close();
        try {
            m_pbd.sync();
        } catch (IOException e) {
            return;
        }
        fail();
    }

    @Test
    public void testIsEmptyWhileClosed() throws Exception {
        System.out.println("Running testIsEmptyWhileClosed");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        m_pbd.close();
        try {
            reader.isEmpty();
        } catch (IOException e) {
            return;
        }
        fail();
    }

    @Test
    public void testPushMaxSize() throws Exception {
        System.out.println("Running testPushMaxSize");
        BBContainer objs[] = new BBContainer[] {
                DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 64)) };
        try {
            m_pbd.push(objs, m_ds);
        } catch (IOException e) {
            return;
        } finally {
            objs[0].discard();
        }
        fail();
    }

    @Test
    public void testOfferMaxSize() throws Exception {
        System.out.println("Running testOfferMaxSize");
        BBContainer cont = DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 64));
        try {
            m_pbd.offer( cont );
        } catch (IOException e) {
            return;
        } finally {
            cont.discard();
        }
        fail();
    }

    @Test
    public void testOverlappingNonces() throws Exception {
        System.out.println("Running testOverlappingNonces");
        for (int i = 0; i < 20; i++) {
            PersistentBinaryDeque pbd = new PersistentBinaryDeque(
                    Integer.toString(i), m_ds, TEST_DIR, logger);
            pbd.offer(defaultContainer());
            pbd.close();
        }

        PersistentBinaryDeque pbd = new PersistentBinaryDeque("1", m_ds, TEST_DIR, logger);
        pbd.close();
    }

    @Test
    public void testNonceWithDots() throws Exception {
        System.out.println("Running testNonceWithDots");
        PersistentBinaryDeque pbd = new PersistentBinaryDeque("ha.ha", m_ds, TEST_DIR, logger);
        pbd.offer(defaultContainer());
        pbd.close();

        pbd = new PersistentBinaryDeque("ha.ha", null, TEST_DIR, logger);
        BinaryDequeReader reader = pbd.openForRead(CURSOR_ID);
        ByteBuffer defaultBuffer = defaultBuffer();
        defaultBuffer.clear();
        pollOnceAndVerify(reader, defaultBuffer);
        pbd.close();
    }

    @Test
    public void testOfferCloseReopenOffer() throws Exception {
        System.out.println("Running testOfferCloseThenReopen");
        //Make it create two full segments
        for (int ii = 0; ii < 96; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }
        File files[] = TEST_DIR.listFiles();
        assertEquals(3, files.length);

        m_pbd.sync();
        m_pbd.close();

        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger);
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        int cnt = reader.getNumObjects();
        assertEquals(cnt, 96);

        for (int ii = 96; ii < 192; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }
        m_pbd.sync();
        cnt = reader.getNumObjects();
        assertEquals(cnt, 192);

        //Now poll all of it and make sure the data is correct
        for (int ii = 0; ii < 192; ii++) {
            ByteBuffer defaultBuffer = getFilledBuffer(ii);
            pollOnceAndVerify(reader, defaultBuffer);
            defaultBuffer.clear();
        }

        //Expect just the current write segment
        List<File> listing = getSortedDirectoryListing();
        assertEquals(1, listing.size());
    }

    @Test
    public void testOfferCloseReopenOfferSmall() throws Exception {
        System.out.println("Running testOfferCloseReopenOfferSmall");
        final String SMALL_TEST_NONCE = "asmall_pbd_nonce";

        PersistentBinaryDeque small_pbd = new PersistentBinaryDeque(SMALL_TEST_NONCE, m_ds, TEST_DIR, logger);
        //Keep in 1 segment.
        for (int ii = 0; ii < 10; ii++) {
            small_pbd.offer(DBBPool.wrapBB(getFilledSmallBuffer(ii)));
        }
        File files[] = TEST_DIR.listFiles();
        //We have the default pbd and new one.
        assertEquals(2, files.length);

        small_pbd.sync();
        small_pbd.close();
        System.gc();
        System.runFinalization();

        small_pbd = new PersistentBinaryDeque(SMALL_TEST_NONCE, m_ds, TEST_DIR, logger);
        BinaryDequeReader reader = small_pbd.openForRead(CURSOR_ID);
        int cnt = reader.getNumObjects();
        assertEquals(cnt, 10);

        for (int ii = 10; ii < 20; ii++) {
            small_pbd.offer(DBBPool.wrapBB(getFilledSmallBuffer(ii)));
        }
        small_pbd.sync();
        cnt = reader.getNumObjects();
        assertEquals(cnt, 20);
        small_pbd.sync();
        small_pbd.close();
        small_pbd = null;
        System.gc();
        System.runFinalization();

        files = TEST_DIR.listFiles();
        assertEquals(3, files.length);

        small_pbd = new PersistentBinaryDeque(SMALL_TEST_NONCE, m_ds, TEST_DIR, logger);
        reader = small_pbd.openForRead(CURSOR_ID);
        //Now poll all of it and make sure the data is correct dont poll everything out.
        for (int ii = 0; ii < 10; ii++) {
            ByteBuffer defaultBuffer = getFilledSmallBuffer(ii);
            pollOnceAndVerify(reader, defaultBuffer);
            defaultBuffer.clear();
        }
        small_pbd.sync();
        small_pbd.close();
        small_pbd = null;
        System.gc();
        System.runFinalization();
    }

    @Test
    public void testOfferCloseReopenOfferLeaveData() throws Exception {
        System.out.println("Running testOfferCloseHoleReopenOffer");
        //Make it create two full segments
        for (int ii = 0; ii < 96; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }
        File files[] = TEST_DIR.listFiles();
        assertEquals(3, files.length);
        m_pbd.sync();
        m_pbd.close();
        m_pbd = null;
        System.gc();
        System.runFinalization();
        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger, true);
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        int cnt = reader.getNumObjects();
        assertEquals(cnt, 96);
        for (int ii = 96; ii < 192; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }
        m_pbd.sync();
        cnt = reader.getNumObjects();
        assertEquals(cnt, 192);

        //Now poll half of it and make sure the data is correct
        for (int ii = 0; ii < 96; ii++) {
            ByteBuffer defaultBuffer = getFilledBuffer(ii);
            pollOnceAndVerify(reader, defaultBuffer);
            defaultBuffer.clear();
        }
        m_pbd.sync();
        m_pbd.close();
        m_pbd = null;
        System.gc();
        System.runFinalization();

        //Expect just the current write segment
        List<File> listing = getSortedDirectoryListing(true);
        assertEquals(3, listing.size());
        //Reload
        m_pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger, true);
        reader = m_pbd.openForRead(CURSOR_ID);
        cnt = reader.getNumObjects();
        assertEquals(cnt, 96);
        //Expect just the current write segment hole should be deleted.
        listing = getSortedDirectoryListing();
        assertEquals(4, listing.size());

        for (int ii = 96; ii < 192; ii++) {
            m_pbd.offer(DBBPool.wrapBB(getFilledBuffer(ii)));
        }
        m_pbd.sync();
        cnt = reader.getNumObjects();
        assertEquals(cnt, 192);
        //Now poll half of it and make sure the data is correct
        for (int ii = 96; ii < 192; ii++) {
            ByteBuffer defaultBuffer = getFilledBuffer(ii);
            pollOnceAndVerify(reader, defaultBuffer);
            defaultBuffer.clear();
        }
        //Expect just the current write segment
        listing = getSortedDirectoryListing();
        assertEquals(3, listing.size());

        //Poll and leave one behind.
        for (int ii = 96; ii < 191; ii++) {
            ByteBuffer defaultBuffer = getFilledBuffer(ii);
            pollOnceAndVerify(reader, defaultBuffer);
            defaultBuffer.clear();
        }
        //Expect just the current write segment
        listing = getSortedDirectoryListing();
        assertEquals(1, listing.size());

        //Push to get more segments at head
        BBContainer objs[] = new BBContainer[]{
            DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32)),
            DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32)),
            DBBPool.wrapBB(ByteBuffer.allocateDirect(1024 * 1024 * 32))};
        m_pbd.push(objs, m_ds);
        listing = getSortedDirectoryListing();
        assertEquals(4, listing.size());

        pollOnce(reader);
        listing = getSortedDirectoryListing();
        assertEquals(3, listing.size());
    }

    @Test
    public void testDeleteOnNonEmptyNextSegment() throws Exception {
        System.out.println("Running testDeleteOnNonEmptyNextSegment");
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        assertNull(reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY));

        final int total = 47;      // Number of buffers it takes to fill a segment

        //Make sure a single file with the appropriate data is created
        for (int i = 0; i < total; i++) {
            m_pbd.offer(defaultContainer());
        }
        assertEquals(1, TEST_DIR.listFiles().length);

        // Read all the buffers from the segment (isEmpty() returns true)
        for (int i = 0; i < total; i++) {
            pollOnce(reader);
        }

        assert(reader.isEmpty());
        File files[] = TEST_DIR.listFiles();
        assertEquals(1, files.length);
        assertEquals("pbd_nonce_0000000001_0000000002.pbd", files[0].getName());
        m_pbd.offer(defaultContainer());

        files = TEST_DIR.listFiles();
        // Make sure a new segment was created and the old segment was deleted
        assertEquals(1, files.length);
        assertEquals("pbd_nonce_0000000003_0000000001.pbd", files[0].getName());
    }

    @Test
    public void testUpdateExtraHeader() throws Exception {
        for (int i = 0; i < 5; ++i) {
            for (int j = 0; j < 2; j++) {
                m_pbd.offer(defaultContainer());
            }
            assertEquals(i + 1, TEST_DIR.listFiles().length);

            final int headerData = i;
            m_pbd.updateExtraHeader(new DeferredSerialization() {
                @Override
                public void serialize(ByteBuffer buf) throws IOException {
                    buf.putInt(headerData);
                }

                @Override
                public int getSerializedSize() throws IOException {
                    return Integer.BYTES;
                }

                @Override
                public void cancel() {}
            });
        }
        assertEquals(6, TEST_DIR.listFiles().length);
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        BBContainer container = reader.getExtraHeader(1);
        try {
            assertNotNull(container);
            ByteBuffer expected = ByteBuffer.allocate(m_ds.getSerializedSize());
            expected.order(ByteOrder.LITTLE_ENDIAN);
            m_ds.serialize(expected);
            expected.rewind();
            assertEquals(expected, container.b());
        } finally {
            container.discard();
        }
        for (int i = 0; i < 5; ++i) {
            container = reader.getExtraHeader(i + 2);
            try {
                assertNotNull(container);
                assertEquals(i, container.b().getInt());
                assertFalse(container.b().hasRemaining());
            } finally {
                container.discard();
            }
        }
    }

    @Test
    public void testCorruptedEntryWithParseAndTruncate() throws Exception {
        m_pbd.offer(defaultContainer());
        corruptLastSegment(ByteBuffer.allocateDirect(35), -35);

        runParseAndTruncateOnNewPbd();
    }

    @Test
    public void testCorruptedEntryWithScanForGap() throws Exception {
        m_pbd.offer(defaultContainer());
        corruptLastSegment(ByteBuffer.allocateDirect(35), -35);

        runScanForGapOnNewPbd();
    }

    @Test
    public void testCorruptedEntryLengthWithParseAndTruncate() throws Exception {
        // set no extraHeader so it is easier to find the first entry header
        m_pbd.updateExtraHeader(null);

        BBContainer container = defaultContainer();
        int origLength = container.b().remaining();
        m_pbd.offer(container);

        ByteBuffer bb = ByteBuffer.allocateDirect(Integer.BYTES);
        bb.putInt(origLength - 100);
        bb.flip();
        corruptLastSegment(bb, PBDSegment.SEGMENT_HEADER_BYTES + PBDSegment.ENTRY_HEADER_TOTAL_BYTES_OFFSET);

        runParseAndTruncateOnNewPbd();
    }

    @Test
    public void testCorruptedEntryLengthWithScanForGap() throws Exception {
        // set no extraHeader so it is easier to find the first entry header
        m_pbd.updateExtraHeader(null);

        BBContainer container = defaultContainer();
        int origLength = container.b().remaining();
        m_pbd.offer(container);

        ByteBuffer bb = ByteBuffer.allocateDirect(Integer.BYTES);
        bb.putInt(origLength - 100);
        bb.flip();
        corruptLastSegment(bb, PBDSegment.SEGMENT_HEADER_BYTES + PBDSegment.ENTRY_HEADER_TOTAL_BYTES_OFFSET);

        runScanForGapOnNewPbd();
    }

    @Test(expected = IOException.class)
    public void testCorruptSegmentHeaderWithParseAndTruncate() throws Exception {
        m_pbd.offer(defaultContainer());
        ByteBuffer bb = ByteBuffer.allocateDirect(Integer.BYTES);
        bb.putInt(100);
        bb.flip();
        corruptLastSegment(bb, PBDSegment.HEADER_NUM_OF_ENTRY_OFFSET);

        runParseAndTruncateOnNewPbd();
    }

    @Test(expected = IOException.class)
    public void testCorruptExtraHeaderWithParseAndTruncate() throws Exception {
        m_pbd.offer(defaultContainer());
        ByteBuffer bb = ByteBuffer.allocateDirect(40);
        corruptLastSegment(bb, PBDSegment.HEADER_EXTRA_HEADER_OFFSET + 15);

        runParseAndTruncateOnNewPbd();
    }

    @Test
    public void testCloseLastReader() throws Exception {
        BinaryDequeReader reader = m_pbd.openForRead(CURSOR_ID);
        pollOnceAndVerify(reader, null);
        m_pbd.closeCursor(CURSOR_ID);
        m_pbd.offer(defaultContainer());
    }

    private void runParseAndTruncateOnNewPbd() throws IOException {
        PersistentBinaryDeque pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger);
        try {
            pbd.parseAndTruncate(b -> null);
            pollOnceAndVerify(pbd.openForRead(CURSOR_ID), null);
            pbd.offer(defaultContainer());
            pollOnceAndVerify(pbd.openForRead(CURSOR_ID), defaultBuffer());
        } finally {
            pbd.close();
        }
    }

    private void runScanForGapOnNewPbd() throws IOException {
        PersistentBinaryDeque pbd = new PersistentBinaryDeque(TEST_NONCE, m_ds, TEST_DIR, logger);
        try {
            pbd.scanEntries(b -> {});
            pollOnceAndVerify(pbd.openForRead(CURSOR_ID), null);
            pbd.offer(defaultContainer());
            pollOnceAndVerify(pbd.openForRead(CURSOR_ID), defaultBuffer());
        } finally {
            pbd.close();
        }
    }

    private void corruptLastSegment(ByteBuffer corruptData, int position) throws Exception {
        File file = getSegmentMap().lastEntry().getValue().file();
        try (FileChannel channel = FileChannel.open(Paths.get(file.getPath()), StandardOpenOption.WRITE)) {
            channel.write(corruptData, position < 0 ? channel.size() + position : position);
        }
    }

    @SuppressWarnings("unchecked")
    private NavigableMap<Long, PBDSegment> getSegmentMap() throws IllegalArgumentException, IllegalAccessException {
        return ((NavigableMap<Long, PBDSegment>) FieldUtils
                .getDeclaredField(PersistentBinaryDeque.class, "m_segments", true).get(m_pbd));
    }

    private BBContainer pollOnceWithoutDiscard(BinaryDequeReader reader) throws IOException {
        BBContainer schema = null;
        try {
            if (reader.isStartOfSegment()) {
                schema = reader.getExtraHeader(-1);
                assertNotNull(schema);
                assertFalse(reader.isEmpty());
            }
            return reader.poll(PersistentBinaryDeque.UNSAFE_CONTAINER_FACTORY);
        } finally {
            if (schema != null) {
                schema.discard();
            }
        }
    }

    private void pollOnce(BinaryDequeReader reader) throws IOException {
        BBContainer retval = pollOnceWithoutDiscard(reader);
        if (retval != null) {
            retval.discard();
        }
    }

    private void pollOnceAndVerify(BinaryDequeReader reader, ByteBuffer destBuf) throws IOException {
        BBContainer retval = pollOnceWithoutDiscard(reader);
        try {
            if (destBuf == null) {
                assertNull(retval);
            } else {
                assertEquals(destBuf, retval.b());
            }
        } finally {
            if (retval != null) {
                retval.discard();
            }
        }
    }
}
