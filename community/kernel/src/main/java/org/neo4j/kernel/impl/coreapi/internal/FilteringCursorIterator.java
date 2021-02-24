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

package org.neo4j.kernel.impl.coreapi.internal;

import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import org.neo4j.graphdb.Entity;
import org.neo4j.internal.kernel.api.Cursor;

import static org.neo4j.io.IOUtils.closeAllSilently;

public class FilteringCursorIterator<CURSOR extends Cursor, E extends Entity> extends PrefetchingEntityResourceIterator<E>
{
    private final CURSOR cursor;
    private final Predicate<CURSOR> predicate;
    private final ToLongFunction<CURSOR> toReferenceFunction;

    public FilteringCursorIterator( CURSOR cursor, Predicate<CURSOR> predicate, ToLongFunction<CURSOR> toReferenceFunction, EntityFactory<E> entityFactory )
    {
        super( entityFactory );
        this.cursor = cursor;
        this.predicate = predicate;
        this.toReferenceFunction = toReferenceFunction;
    }

    @Override
    long fetchNext()
    {
        boolean hasNext;
        do
        {
            hasNext = cursor.next();
        }
        while ( hasNext && !matches() );

        if ( hasNext )
        {
            return toReferenceFunction.applyAsLong( cursor );
        }
        close();
        return NO_ID;
    }

    private boolean matches()
    {
        return predicate.test( cursor );
    }

    @Override
    void closeResources()
    {
        closeAllSilently( cursor );
    }
}
