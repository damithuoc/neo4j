/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.causalclustering.catchup.storecopy;

import org.junit.Test;

import java.io.File;

import org.neo4j.helpers.collection.Iterables;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.configuration.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class CopiedStoreRecoveryTest
{
    @Test
    public void shouldThrowIfAlreadyShutdown()
    {
        // Given
        CopiedStoreRecovery copiedStoreRecovery =
                new CopiedStoreRecovery( Config.defaults(), Iterables.empty(), mock( PageCache.class ) );
        copiedStoreRecovery.shutdown();

        try
        {
            // when
            copiedStoreRecovery.recoverCopiedStore( new File( "nowhere" ) );
            fail( "should have thrown" );
        }
        catch ( DatabaseShutdownException ex )
        {
            // then
            assertEquals( "Abort store-copied store recovery due to database shutdown", ex.getMessage() );
        }
    }
}
