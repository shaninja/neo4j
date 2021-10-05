/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.database.transaction;

import org.eclipse.collections.impl.factory.primitive.LongObjectMaps;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;

import org.neo4j.io.fs.DelegatingStoreChannel;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.NoSuchTransactionException;
import org.neo4j.kernel.impl.transaction.log.TransactionCursor;
import org.neo4j.kernel.impl.transaction.log.files.LogFile;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;

import static org.neo4j.util.Preconditions.requirePositive;

public class TransactionLogServiceImpl implements TransactionLogService
{
    private final LogFiles logFiles;
    private final LogicalTransactionStore transactionStore;
    private final Lock pruneLock;

    public TransactionLogServiceImpl( LogFiles logFiles, LogicalTransactionStore transactionStore, Lock pruneLock )
    {
        this.logFiles = logFiles;
        this.transactionStore = transactionStore;
        this.pruneLock = pruneLock;
    }

    @Override
    public TransactionLogChannels logFilesChannels( long startingTxId ) throws IOException
    {
        requirePositive( startingTxId );
        LogPosition minimalLogPosition = getLogPosition( startingTxId );
        // prevent pruning while we build log channels to avoid cases when we will actually prevent pruning to remove files (on some file systems and OSs),
        // or unexpected exceptions while traversing files
        pruneLock.lock();
        try
        {
            long minimalVersion = minimalLogPosition.getLogVersion();
            var logFile = logFiles.getLogFile();
            long highestLogVersion = logFile.getHighestLogVersion();

            var channels = collectChannels( startingTxId, minimalLogPosition, minimalVersion, logFile, highestLogVersion );
            return new TransactionLogChannels( channels );
        }
        finally
        {
            pruneLock.unlock();
        }
    }

    private ArrayList<LogChannel> collectChannels( long startingTxId, LogPosition minimalLogPosition, long minimalVersion, LogFile logFile,
            long highestLogVersion ) throws IOException
    {
        int exposedChannels = (int) ((highestLogVersion - minimalVersion) + 1);
        var channels = new ArrayList<LogChannel>( exposedChannels );
        var internalChannels = LongObjectMaps.mutable.<StoreChannel>ofInitialCapacity( exposedChannels );
        for ( long version = minimalVersion; version <= highestLogVersion; version++ )
        {
            var lastCommittedTxId = logFileTransactionId( startingTxId, minimalVersion, logFile, version );
            var readOnlyStoreChannel = new ReadOnlyStoreChannel( logFile, version );
            if ( version == minimalVersion )
            {
                readOnlyStoreChannel.position( minimalLogPosition.getByteOffset() );
            }
            internalChannels.put( version, readOnlyStoreChannel );
            channels.add( new LogChannel( lastCommittedTxId, readOnlyStoreChannel ) );
        }
        logFile.registerExternalReaders( internalChannels );
        return channels;
    }

    private long logFileTransactionId( long startingTxId, long minimalVersion, LogFile logFile, long version ) throws IOException
    {
        return version == minimalVersion ? startingTxId : logFile.extractHeader( version ).getLastCommittedTxId();
    }

    private LogPosition getLogPosition( long startingTxId ) throws IOException
    {
        try ( TransactionCursor transactionCursor = transactionStore.getTransactions( startingTxId ) )
        {
            return transactionCursor.position();
        }
        catch ( NoSuchTransactionException e )
        {
            throw new IllegalArgumentException( "Transaction id " + startingTxId + " not found in transaction logs.", e );
        }
    }

    private static class ReadOnlyStoreChannel extends DelegatingStoreChannel<StoreChannel>
    {
        private final LogFile logFile;
        private final long version;

        ReadOnlyStoreChannel( LogFile logFile, long version ) throws IOException
        {
            super( logFile.openForVersion( version ) );
            this.logFile = logFile;
            this.version = version;
        }

        @Override
        public long write( ByteBuffer[] srcs, int offset, int length ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public int write( ByteBuffer src ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public void writeAll( ByteBuffer src ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public void writeAll( ByteBuffer src, long position ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public StoreChannel truncate( long size ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public long write( ByteBuffer[] srcs ) throws IOException
        {
            throw new UnsupportedOperationException( "Read only channel does not support any write operations." );
        }

        @Override
        public void close() throws IOException
        {
            logFile.unregisterExternalReader( version, this );
            super.close();
        }
    }
}