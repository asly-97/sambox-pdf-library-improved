/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.input;

import static java.util.stream.LongStream.empty;

import java.io.IOException;
import java.io.InputStream;
import java.util.PrimitiveIterator.OfLong;
import java.util.stream.LongStream;
import java.util.stream.LongStream.Builder;

import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.cos.COSNumber;
import org.sejda.sambox.cos.COSStream;
import org.sejda.sambox.xref.CompressedXrefEntry;
import org.sejda.sambox.xref.XrefEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for an xref stream parser. Implementors will decide what to do when the parser finds a new trailer or a
 * new entries.
 * 
 * @author Andrea Vacondio
 * @see AbstractXrefTableParser
 */
abstract class AbstractXrefStreamParser
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractXrefStreamParser.class);

    private final COSParser parser;

    AbstractXrefStreamParser(COSParser parser)
    {
        this.parser = parser;
    }

    /**
     * Action to perform when a trailer is found
     * 
     * @param trailer
     */
    abstract void onTrailerFound(COSDictionary trailer);

    /**
     * Action to perform when an {@link XrefEntry} is found
     * 
     * @param entry
     */
    abstract void onEntryFound(XrefEntry entry);

    /**
     * Parse the xref object stream.
     * 
     * @param streamObjectOffset xref stream object offset
     * @return the stream dictionary
     * @throws IOException
     */
    COSDictionary parse(long streamObjectOffset) throws IOException
    {
        LOG.debug("Parsing xref stream at offset " + streamObjectOffset);
        parser.position(streamObjectOffset);
        parser.skipIndirectObjectDefinition();
        parser.skipSpaces();

        COSDictionary dictionary = parser.nextDictionary();
        try (COSStream xrefStream = parser.nextStream(dictionary))
        {
            onTrailerFound(dictionary);
            parseStream(xrefStream);
        }
        LOG.debug("Done parsing xref stream");
        return dictionary;
    }

    void parseStream(COSStream xrefStream) throws IOException
    {
        LongStream objectNumbers = empty();
        COSArray index = xrefStream.getDictionaryObject(COSName.INDEX, COSArray.class);

        if (index == null)
        {
            LOG.debug("No index found for xref stream, using default values");
            // Chap 7.5.8.3 of PDF32000-1:2008: a missing /Index defaults to [0 Size],
            // that is the object numbers from 0 to Size - 1
            objectNumbers = LongStream.range(0, xrefStream.getInt(COSName.SIZE));
        }
        else
        {
            LOG.debug("Index found, now retrieving expected object numbers");
            if (index.size() % 2 != 0)
            {
                throw new IOException(
                        "Invalid xref stream /Index array, expected an even number of elements but found "
                                + index.size());
            }
            Builder builder = LongStream.builder();
            for (int i = 0; i < index.size(); i += 2)
            {
                if (!(index.get(i) instanceof COSNumber) || !(index.get(i + 1) instanceof COSNumber))
                {
                    throw new IOException(
                            "Invalid xref stream /Index array, expected pairs of integers");
                }
                long start = ((COSNumber) index.get(i)).longValue();
                long count = ((COSNumber) index.get(i + 1)).longValue();
                if (count < 0)
                {
                    throw new IOException(
                            "Invalid xref stream /Index array, negative entry count " + count);
                }
                LOG.trace(String.format("Adding expected range for %d objects starting at %d", count,
                        start));
                LongStream.range(start, start + count).forEach(builder::add);
            }
            objectNumbers = builder.build();

        }
        COSArray xrefFormat = (COSArray) xrefStream.getDictionaryObject(COSName.W);
        if (xrefFormat == null || xrefFormat.size() < 3)
        {
            throw new IOException("Invalid xref stream, missing or incomplete /W array");
        }
        int w0 = xrefFormat.getInt(0);
        int w1 = xrefFormat.getInt(1);
        int w2 = xrefFormat.getInt(2);
        if (w0 < 0 || w1 < 0 || w2 < 0)
        {
            throw new IOException("Invalid xref stream /W array, negative field length");
        }
        int lineSize = w0 + w1 + w2;
        if (lineSize == 0)
        {
            throw new IOException("Invalid xref stream /W array, zero entry length");
        }
        try (InputStream stream = xrefStream.getUnfilteredStream())
        {
            OfLong objectIds = objectNumbers.iterator();
            while (stream.available() > 0 && objectIds.hasNext())
            {

                Long objectId = objectIds.next();
                byte[] currLine = new byte[lineSize];
                readEntry(stream, currLine);
                // fields are accumulated into longs: the second field of an in-use entry is a
                // byte offset and can legitimately exceed 2^31 in large files
                long type = (w0 == 0) ? 1 : 0;
                int i = 0;
                /*
                 * Grabs the number of bytes specified for the first column in the W array and stores it.
                 */
                for (i = 0; i < w0; i++)
                {
                    type = (type << 8) | (currLine[i] & 0x00ffL);
                }
                long field1 = 0;
                for (i = 0; i < w1; i++)
                {
                    field1 = (field1 << 8) | (currLine[i + w0] & 0x00ffL);
                }
                long field2 = 0;
                for (i = 0; i < w2; i++)
                {
                    field2 = (field2 << 8) | (currLine[i + w0 + w1] & 0x00ffL);
                }
                try
                {
                    switch ((int) type)
                    {
                    case 0 -> onEntryFound(XrefEntry.freeEntry(objectId, (int) field2));
                    case 1 -> onEntryFound(XrefEntry.inUseEntry(objectId, field1, (int) field2));
                    case 2 -> onEntryFound(
                            CompressedXrefEntry.compressedEntry(objectId, field1, (int) field2));
                    default -> LOG.warn("Unknown xref entry type " + type);
                    }
                }
                catch (IllegalArgumentException e)
                {
                    // the entry consumer rejected the entry (ex. a negative byte offset): same
                    // contract as the xref table parser, signal a corrupted xref with an
                    // IOException so the caller can apply its fallback strategy
                    throw new IOException("Corrupted xref stream entry for object " + objectId, e);
                }
            }
        }
    }

    /**
     * Reads one full entry from the stream: a single {@link InputStream#read(byte[])} call is
     * not required to fill the array, and a short read would silently misalign every
     * following entry.
     *
     * @param stream
     * @param entry
     * @throws IOException if the stream ends before a full entry could be read
     */
    private void readEntry(InputStream stream, byte[] entry) throws IOException
    {
        int read = 0;
        while (read < entry.length)
        {
            int n = stream.read(entry, read, entry.length - read);
            if (n < 0)
            {
                throw new IOException(
                        "Truncated xref stream, expected entries of " + entry.length + " bytes");
            }
            read += n;
        }
    }

    COSParser parser()
    {
        return parser;
    }
}
