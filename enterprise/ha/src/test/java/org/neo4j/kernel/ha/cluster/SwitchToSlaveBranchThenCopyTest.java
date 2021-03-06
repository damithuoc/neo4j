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
package org.neo4j.kernel.ha.cluster;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.neo4j.backup.OnlineBackupKernelExtension;
import org.neo4j.cluster.ClusterSettings;
import org.neo4j.cluster.InstanceId;
import org.neo4j.cluster.member.ClusterMemberAvailability;
import org.neo4j.com.Response;
import org.neo4j.com.storecopy.MoveAfterCopy;
import org.neo4j.com.storecopy.StoreCopyClient;
import org.neo4j.com.storecopy.TransactionCommittingResponseUnpacker;
import org.neo4j.com.storecopy.TransactionObligationFulfiller;
import org.neo4j.function.Suppliers;
import org.neo4j.helpers.CancellationRequest;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.DatabaseAvailability;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.ha.BranchedDataException;
import org.neo4j.kernel.ha.BranchedDataPolicy;
import org.neo4j.kernel.ha.DelegateInvocationHandler;
import org.neo4j.kernel.ha.MasterClient320;
import org.neo4j.kernel.ha.PullerFactory;
import org.neo4j.kernel.ha.SlaveUpdatePuller;
import org.neo4j.kernel.ha.UpdatePuller;
import org.neo4j.kernel.ha.UpdatePullerScheduler;
import org.neo4j.kernel.ha.cluster.member.ClusterMember;
import org.neo4j.kernel.ha.cluster.member.ClusterMembers;
import org.neo4j.kernel.ha.cluster.modeswitch.HighAvailabilityModeSwitcher;
import org.neo4j.kernel.ha.com.RequestContextFactory;
import org.neo4j.kernel.ha.com.master.HandshakeResult;
import org.neo4j.kernel.ha.com.slave.MasterClient;
import org.neo4j.kernel.ha.com.slave.MasterClientResolver;
import org.neo4j.kernel.ha.com.slave.SlaveServer;
import org.neo4j.kernel.ha.id.HaIdGeneratorFactory;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.logging.NullLogService;
import org.neo4j.kernel.impl.store.MismatchingStoreIdException;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.kernel.impl.store.TransactionId;
import org.neo4j.kernel.impl.transaction.TransactionStats;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.state.DataSourceManager;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.impl.util.watcher.FileSystemWatcherService;
import org.neo4j.kernel.internal.locker.StoreLockerLifecycleAdapter;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobScheduler;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.neo4j.com.StoreIdTestFactory.newStoreIdForCurrentVersion;

public class SwitchToSlaveBranchThenCopyTest
{
    private final UpdatePuller updatePuller = mockWithLifecycle( SlaveUpdatePuller.class );
    private final PullerFactory pullerFactory = mock( PullerFactory.class );
    private final FileSystemAbstraction fs = mock( FileSystemAbstraction.class );
    private final MasterClient masterClient = mock( MasterClient.class );
    private final RequestContextFactory requestContextFactory = mock( RequestContextFactory.class );
    private final StoreId storeId = newStoreIdForCurrentVersion( 42, 42, 42, 42 );

    @Test
    public void shouldRestartServicesIfCopyStoreFails() throws Throwable
    {
        when( updatePuller.tryPullUpdates() ).thenReturn( true );

        PageCache pageCacheMock = mockPageCache();

        StoreCopyClient storeCopyClient = mock( StoreCopyClient.class );

        doThrow( new RuntimeException() ).doNothing().when( storeCopyClient ).copyStore(
                any( StoreCopyClient.StoreCopyRequester.class ),
                any( CancellationRequest.class ),
                any( MoveAfterCopy.class ) );

        SwitchToSlaveBranchThenCopy switchToSlave = newSwitchToSlaveSpy( pageCacheMock, storeCopyClient );

        URI localhost = getLocalhostUri();
        try
        {
            switchToSlave.switchToSlave( mock( LifeSupport.class ), localhost, localhost,
                    mock( CancellationRequest.class ) );
            fail( "Should have thrown an Exception" );
        }
        catch ( RuntimeException e )
        {
            verify( requestContextFactory, never() ).start();
            // Store should have been deleted due to failure in copy
            verify( switchToSlave ).cleanStoreDir();

            // Try again, should succeed
            switchToSlave.switchToSlave( mock( LifeSupport.class ), localhost, localhost,
                    mock( CancellationRequest.class ) );
            verify( requestContextFactory ).start();
        }
    }

    private PageCache mockPageCache() throws IOException
    {
        PageCache pageCacheMock = mock( PageCache.class );
        PagedFile pagedFileMock = mock( PagedFile.class );
        when( pagedFileMock.getLastPageId() ).thenReturn( 1L );
        when( pageCacheMock.map( any( File.class ), anyInt() ) ).thenThrow( new IOException() )
                .thenThrow( new IOException() ).thenReturn( pagedFileMock );
        return pageCacheMock;
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void shouldHandleBranchedStoreWhenMyStoreIdDiffersFromMasterStoreId() throws Throwable
    {
        // Given
        SwitchToSlaveBranchThenCopy switchToSlave = newSwitchToSlaveSpy();
        URI me = new URI( "cluster://localhost?serverId=2" );

        MasterClient masterClient = mock( MasterClient.class );
        Response<HandshakeResult> response = mock( Response.class );
        when( response.response() ).thenReturn( new HandshakeResult( 1, 2 ) );
        when( masterClient.handshake( anyLong(), any( StoreId.class ) ) ).thenReturn( response );

        StoreId storeId = newStoreIdForCurrentVersion( 1, 2, 3, 4 );

        TransactionIdStore transactionIdStore = mock( TransactionIdStore.class );
        when( transactionIdStore.getLastCommittedTransaction() ).thenReturn( new TransactionId( 42, 42, 42 ) );
        when( transactionIdStore.getLastCommittedTransactionId() ).thenReturn( TransactionIdStore.BASE_TX_ID );

        // When
        try
        {
            switchToSlave.checkDataConsistency( masterClient, transactionIdStore, storeId,
                    new URI( "cluster://localhost?serverId=1" ), me, CancellationRequest.NEVER_CANCELLED );
            fail( "Should have thrown " + MismatchingStoreIdException.class.getSimpleName() + " exception" );
        }
        catch ( MismatchingStoreIdException e )
        {
            // good we got the expected exception
        }

        // Then
        verify( switchToSlave ).stopServicesAndHandleBranchedStore( any( BranchedDataPolicy.class ) );
    }

    @Test
    public void shouldHandleBranchedStoreWhenHandshakeFailsWithBranchedDataException() throws Throwable
    {
        // Given
        SwitchToSlaveBranchThenCopy switchToSlave = newSwitchToSlaveSpy();
        URI masterUri = new URI( "cluster://localhost?serverId=1" );
        URI me = new URI( "cluster://localhost?serverId=2" );

        MasterClient masterClient = mock( MasterClient.class );
        when( masterClient.handshake( anyLong(), any( StoreId.class ) ) ).thenThrow( new BranchedDataException( "" ) );

        TransactionIdStore transactionIdStore = mock( TransactionIdStore.class );
        when( transactionIdStore.getLastCommittedTransaction() ).thenReturn( new TransactionId( 42, 42, 42 ) );
        when( transactionIdStore.getLastCommittedTransactionId() ).thenReturn( TransactionIdStore.BASE_TX_ID );

        // When
        try
        {
            switchToSlave.checkDataConsistency( masterClient, transactionIdStore, storeId, masterUri, me,
                    CancellationRequest.NEVER_CANCELLED );
            fail( "Should have thrown " + BranchedDataException.class.getSimpleName() + " exception" );
        }
        catch ( BranchedDataException e )
        {
            // good we got the expected exception
        }

        // Then
        verify( switchToSlave ).stopServicesAndHandleBranchedStore( any( BranchedDataPolicy.class ) );
    }

    @Test
    public void shouldReturnNullIfWhenFailingToPullingUpdatesFromMaster() throws Throwable
    {
        // Given
        SwitchToSlaveBranchThenCopy switchToSlave = newSwitchToSlaveSpy();

        when( fs.fileExists( any( File.class ) ) ).thenReturn( true );
        when( updatePuller.tryPullUpdates() ).thenReturn( false );

        // when
        URI localhost = getLocalhostUri();
        URI uri = switchToSlave.switchToSlave( mock( LifeSupport.class ), localhost, localhost,
                mock( CancellationRequest.class ) );

        // then
        assertNull( uri );
    }

    @Test
    public void updatesPulledAndPullingScheduledOnSwitchToSlave() throws Throwable
    {
        SwitchToSlaveBranchThenCopy switchToSlave = newSwitchToSlaveSpy();

        when( fs.fileExists( any( File.class ) ) ).thenReturn( true );
        JobScheduler jobScheduler = mock( JobScheduler.class );
        LifeSupport communicationLife = mock( LifeSupport.class );
        URI localhost = getLocalhostUri();
        final UpdatePullerScheduler pullerScheduler =
                new UpdatePullerScheduler( jobScheduler, NullLogProvider.getInstance(), updatePuller, 10L );

        when( pullerFactory.createUpdatePullerScheduler( updatePuller ) ).thenReturn( pullerScheduler );
        // emulate lifecycle start call on scheduler
        doAnswer( invocationOnMock ->
        {
            pullerScheduler.init();
            return null;
        } ).when( communicationLife ).start();

        switchToSlave.switchToSlave( communicationLife, localhost, localhost, mock( CancellationRequest.class ) );

        verify( updatePuller ).tryPullUpdates();
        verify( communicationLife ).add( pullerScheduler );
        verify( jobScheduler ).scheduleRecurring( eq( JobScheduler.Groups.pullUpdates ), any( Runnable.class ),
                eq( 10L ), eq( 10L ), eq( TimeUnit.MILLISECONDS ) );
    }

    private URI getLocalhostUri() throws URISyntaxException
    {
        return new URI( "cluster://127.0.0.1?serverId=1" );
    }

    private SwitchToSlaveBranchThenCopy newSwitchToSlaveSpy() throws Exception
    {
        PageCache pageCacheMock = mock( PageCache.class );
        PagedFile pagedFileMock = mock( PagedFile.class );
        when( pagedFileMock.getLastPageId() ).thenReturn( 1L );
        when( pageCacheMock.map( any( File.class ), anyInt() ) ).thenReturn( pagedFileMock );
        FileSystemAbstraction fileSystemAbstraction = mock( FileSystemAbstraction.class );
        when( fileSystemAbstraction.streamFilesRecursive( any( File.class ) ) )
                .thenAnswer( f -> Stream.empty() );

        StoreCopyClient storeCopyClient = mock( StoreCopyClient.class );

        return newSwitchToSlaveSpy( pageCacheMock, storeCopyClient );
    }

    @SuppressWarnings( "unchecked" )
    private SwitchToSlaveBranchThenCopy newSwitchToSlaveSpy( PageCache pageCacheMock, StoreCopyClient storeCopyClient )
    {
        ClusterMembers clusterMembers = mock( ClusterMembers.class );
        ClusterMember master = mock( ClusterMember.class );
        when( master.getStoreId() ).thenReturn( storeId );
        when( master.getHARole() ).thenReturn( HighAvailabilityModeSwitcher.MASTER );
        when( master.hasRole( eq( HighAvailabilityModeSwitcher.MASTER ) ) ).thenReturn( true );
        when( master.getInstanceId() ).thenReturn( new InstanceId( 1 ) );
        when( clusterMembers.getMembers() ).thenReturn( singletonList( master ) );

        Dependencies resolver = new Dependencies();
        DatabaseAvailability databaseAvailability = mock( DatabaseAvailability.class );
        when( databaseAvailability.isStarted() ).thenReturn( true );
        resolver.satisfyDependencies( requestContextFactory, clusterMembers,
                mock( TransactionObligationFulfiller.class ),
                mock( OnlineBackupKernelExtension.class ),
                mock( IndexConfigStore.class ),
                mock( TransactionCommittingResponseUnpacker.class ),
                mock( DataSourceManager.class ),
                mock( StoreLockerLifecycleAdapter.class ),
                mock( FileSystemWatcherService.class ),
                databaseAvailability
                );

        NeoStoreDataSource dataSource = mock( NeoStoreDataSource.class );
        when( dataSource.getStoreId() ).thenReturn( storeId );
        when( dataSource.getDependencyResolver() ).thenReturn( resolver );

        TransactionStats transactionCounters = mock( TransactionStats.class );
        when( transactionCounters.getNumberOfActiveTransactions() ).thenReturn( 0L );

        Response<HandshakeResult> response = mock( Response.class );
        when( response.response() ).thenReturn( new HandshakeResult( 42, 2 ) );
        when( masterClient.handshake( anyLong(), any( StoreId.class ) ) ).thenReturn( response );
        when( masterClient.getProtocolVersion() ).thenReturn( MasterClient320.PROTOCOL_VERSION );

        TransactionIdStore transactionIdStoreMock = mock( TransactionIdStore.class );
        // note that the checksum (the second member of the array) is the same as the one in the handshake mock above
        when( transactionIdStoreMock.getLastCommittedTransaction() ).thenReturn( new TransactionId( 42, 42, 42 ) );

        MasterClientResolver masterClientResolver = mock( MasterClientResolver.class );
        when( masterClientResolver.instantiate( anyString(), anyInt(), anyString(), any( Monitors.class ),
                argThat( storeId -> true ), any( LifeSupport.class ) ) ).thenReturn( masterClient );

        return spy( new SwitchToSlaveBranchThenCopy( new File( "" ), NullLogService.getInstance(),
                configMock(),
                mock( HaIdGeneratorFactory.class ),
                mock( DelegateInvocationHandler.class ),
                mock( ClusterMemberAvailability.class ),
                requestContextFactory,
                pullerFactory, masterClientResolver,
                mock( SwitchToSlave.Monitor.class ),
                storeCopyClient,
                Suppliers.singleton( dataSource ),
                Suppliers.singleton( transactionIdStoreMock ),
                slave ->
                {
                    SlaveServer server = mock( SlaveServer.class );
                    InetSocketAddress inetSocketAddress = InetSocketAddress.createUnresolved( "localhost", 42 );

                    when( server.getSocketAddress() ).thenReturn( inetSocketAddress );
                    return server;
                }, updatePuller, pageCacheMock, mock( Monitors.class ), transactionCounters ) );
    }

    private Config configMock()
    {
        return Config.defaults( ClusterSettings.server_id, "1" );
    }

    private <T> T mockWithLifecycle( Class<T> clazz )
    {
        return mock( clazz, withSettings().extraInterfaces( Lifecycle.class ) );
    }
}
