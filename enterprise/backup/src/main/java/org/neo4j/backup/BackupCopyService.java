/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.neo4j.com.storecopy.FileMoveAction;
import org.neo4j.com.storecopy.FileMoveProvider;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.configuration.Config;

import static java.lang.String.format;
import static org.neo4j.graphdb.factory.GraphDatabaseSettings.logs_directory;
import static org.neo4j.graphdb.factory.GraphDatabaseSettings.store_internal_log_path;

class BackupCopyService
{
    private static final int MAX_OLD_BACKUPS = 1000;

    private final PageCache pageCache;

    private final FileMoveProvider fileMoveProvider;

    BackupCopyService( PageCache pageCache, FileMoveProvider fileMoveProvider )
    {
        this.pageCache = pageCache;
        this.fileMoveProvider = fileMoveProvider;
    }

    public void moveBackupLocation( Path oldLocation, Path newLocation ) throws IOException
    {
        try
        {
            File source = oldLocation.toFile();
            File target = newLocation.toFile();
            Iterator<FileMoveAction> moves = fileMoveProvider.traverseForMoving( source ).iterator();
            while ( moves.hasNext() )
            {
                moves.next().move( target );
            }
        }
        catch ( IOException e )
        {
            throw new IOException( "Failed to rename backup directory from " + oldLocation + " to " + newLocation, e );
        }
    }

    public void clearLogs( Path neo4jHome )
    {
        File logsDirectory = Config.defaults( logs_directory, neo4jHome.toString() ).get( store_internal_log_path );
        logsDirectory.delete();
    }

    boolean backupExists( Path destination )
    {
        File[] listFiles = pageCache.getCachedFileSystem().listFiles( destination.toFile() );
        return listFiles != null && listFiles.length > 0;
    }

    Path findNewBackupLocationForBrokenExisting( Path existingBackup ) throws IOException
    {
        return findAnAvailableBackupLocation( existingBackup, "%s.err.%d" );
    }

    Path findAnAvailableLocationForNewFullBackup( Path desiredBackupLocation ) throws IOException
    {
        return findAnAvailableBackupLocation( desiredBackupLocation, "%s.temp.%d" );
    }

    /**
     * Given a desired file name, find an available name that is similar to the given one that doesn't conflict with already existing backups
     *
     * @param file desired ideal file name
     * @param pattern pattern to follow if desired name is taken (requires %s for original name, and %d for iteration)
     * @return the resolved file name which can be the original desired, or a variation that matches the pattern
     */
    private Path findAnAvailableBackupLocation( Path file, String pattern ) throws IOException
    {
        if ( backupExists( file ) )
        {
            // find alternative name
            final AtomicLong counter = new AtomicLong( 0 );
            Consumer<Path> countNumberOfFilesProcessedForPotentialErrorMessage =
                    generatedBackupFile -> counter.getAndIncrement();

            return availableAlternativeNames( file, pattern )
                    .peek( countNumberOfFilesProcessedForPotentialErrorMessage )
                    .filter( f -> !backupExists( f ) )
                    .findFirst()
                    .orElseThrow( noFreeBackupLocation( file, counter ) );
        }
        return file;
    }

    private static Supplier<RuntimeException> noFreeBackupLocation( Path file, AtomicLong counter )
    {
        return () -> new RuntimeException( String.format(
                "Unable to find a free backup location for the provided %s. %d possible locations were already taken.",
                file, counter.get() ) );
    }

    private static Stream<Path> availableAlternativeNames( Path originalBackupDirectory, String pattern )
    {
        return IntStream.range( 0, MAX_OLD_BACKUPS )
                .mapToObj( iteration -> alteredBackupDirectoryName( pattern, originalBackupDirectory, iteration ) );
    }

    private static Path alteredBackupDirectoryName( String pattern, Path directory, int iteration )
    {
        Path directoryName = directory.getName( directory.getNameCount() - 1 );
        return directory.resolveSibling( format( pattern, directoryName, iteration ) );
    }
}
