/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.bolt.v1.runtime;

import java.time.Clock;
import java.util.Optional;

import org.neo4j.bolt.runtime.BoltQuerySource;
import org.neo4j.bolt.runtime.BoltResult;
import org.neo4j.bolt.runtime.BoltResultHandle;
import org.neo4j.bolt.runtime.StatementMetadata;
import org.neo4j.bolt.runtime.StatementProcessor;
import org.neo4j.bolt.runtime.TransactionStateMachineSPI;
import org.neo4j.bolt.security.auth.AuthenticationResult;
import org.neo4j.bolt.v1.runtime.bookmarking.Bookmark;
import org.neo4j.bolt.v1.runtime.spi.BookmarkResult;
import org.neo4j.cypher.InvalidSemanticsException;
import org.neo4j.function.ThrowingConsumer;
import org.neo4j.graphdb.TransactionTerminatedException;
import org.neo4j.internal.kernel.api.exceptions.KernelException;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.impl.query.QueryExecutionKernelException;
import org.neo4j.values.virtual.MapValue;

public class TransactionStateMachine implements StatementProcessor
{
    final TransactionStateMachineSPI spi;
    final MutableTransactionState ctx;
    State state = State.AUTO_COMMIT;

    TransactionStateMachine( TransactionStateMachineSPI spi, AuthenticationResult authenticationResult, Clock clock )
    {
        this.spi = spi;
        ctx = new MutableTransactionState( authenticationResult, clock );
    }

    public State state()
    {
        return state;
    }

    private void before()
    {
        if ( ctx.currentTransaction != null )
        {
            spi.bindTransactionToCurrentThread( ctx.currentTransaction );
        }
    }

    @Override
    public void beginTransaction( Bookmark bookmark ) throws KernelException
    {
        before();
        try
        {
            ensureNoPendingTerminationNotice();

            state = state.beginTransaction( bookmark, ctx, spi );
        }
        finally
        {
            after();
        }
    }

    @Override
    public StatementMetadata run( String statement, MapValue params ) throws KernelException
    {
        before();
        try
        {
            ensureNoPendingTerminationNotice();

            state = state.run( ctx, spi, statement, params );

            return ctx.currentStatementMetadata;
        }
        finally
        {
            after();
        }
    }

    @Override
    public void streamResult( ThrowingConsumer<BoltResult, Exception> resultConsumer ) throws Exception
    {
        before();
        try
        {
            ensureNoPendingTerminationNotice();

            state.streamResult( ctx, resultConsumer );
        }
        finally
        {
            after();
        }
    }

    @Override
    public void commitTransaction() throws KernelException
    {
        before();
        try
        {
            ensureNoPendingTerminationNotice();

            state = state.commitTransaction( ctx, spi );
        }
        catch ( TransactionFailureException ex )
        {
            state = State.AUTO_COMMIT;
            throw ex;
        }
        finally
        {
            after();
        }
    }

    @Override
    public void rollbackTransaction() throws KernelException
    {
        before();
        try
        {
            ensureNoPendingTerminationNotice();
            state = state.rollbackTransaction( ctx, spi );
        }
        finally
        {
            after();
        }
    }

    @Override
    public boolean hasOpenStatement()
    {
        return ctx.currentResultHandle != null;
    }

    /**
     * Rollback and close transaction. Move back to {@link State#AUTO_COMMIT}.
     * <p>
     * <b>Warning:</b>This method should only be called by the bolt worker thread during it's regular message
     * processing. It is wrong to call it from a different thread because kernel transactions are not thread-safe.
     *
     * @throws TransactionFailureException when transaction fails to close.
     */
    @Override
    public void reset() throws TransactionFailureException
    {
        state.terminateQueryAndRollbackTransaction( ctx );
        state = State.AUTO_COMMIT;
    }

    private void after()
    {
        spi.unbindTransactionFromCurrentThread();
    }

    @Override
    public void markCurrentTransactionForTermination()
    {
        KernelTransaction tx = ctx.currentTransaction;
        if ( tx != null )
        {
            tx.markForTermination( Status.Transaction.Terminated );
        }
    }

    @Override
    public void validateTransaction() throws KernelException
    {
        KernelTransaction tx = ctx.currentTransaction;

        if ( tx != null )
        {
            Optional<Status> statusOpt = tx.getReasonIfTerminated();

            if ( statusOpt.isPresent() )
            {
                if ( statusOpt.get().code().classification().rollbackTransaction() )
                {
                    ctx.pendingTerminationNotice = statusOpt.get();

                    reset();
                }
            }
        }
    }

    private void ensureNoPendingTerminationNotice()
    {
        if ( ctx.pendingTerminationNotice != null )
        {
            Status status = ctx.pendingTerminationNotice;

            ctx.pendingTerminationNotice = null;

            throw new TransactionTerminatedException( status );
        }
    }

    @Override
    public boolean hasTransaction()
    {
        return state == State.EXPLICIT_TRANSACTION;
    }

    @Override
    public void setQuerySource( BoltQuerySource querySource )
    {
        this.ctx.querySource = querySource;
    }

    enum State
    {
        AUTO_COMMIT
                {
                    @Override
                    State beginTransaction( Bookmark bookmark, MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException
                    {
                        ctx.currentTransaction = spi.beginTransaction( ctx.loginContext );

                        if ( bookmark != null )
                        {
                            spi.awaitUpToDate( bookmark.txId() );
                            ctx.currentResult = new BookmarkResult( bookmark );
                        }
                        else
                        {
                            ctx.currentResult = BoltResult.EMPTY;
                        }

                        return EXPLICIT_TRANSACTION;
                    }

                    @Override
                    State run( MutableTransactionState ctx, TransactionStateMachineSPI spi, String statement, MapValue params ) throws KernelException
                    {
                        if ( statement.isEmpty() )
                        {
                            statement = ctx.lastStatement;
                        }
                        else
                        {
                            ctx.lastStatement = statement;
                        }
                        execute( ctx, spi, statement, params, spi.isPeriodicCommit( statement ) );
                        return AUTO_COMMIT;
                    }

                    void execute( MutableTransactionState ctx, TransactionStateMachineSPI spi, String statement, MapValue params, boolean isPeriodicCommit )
                            throws KernelException
                    {
                        // only acquire a new transaction when the statement does not contain periodic commit
                        if ( !isPeriodicCommit )
                        {
                            ctx.currentTransaction = spi.beginTransaction( ctx.loginContext );
                        }

                        boolean failed = true;
                        try
                        {
                            BoltResultHandle resultHandle = spi.executeQuery( ctx.querySource, ctx.loginContext, statement, params );
                            startExecution( ctx, resultHandle );
                            failed = false;
                        }
                        finally
                        {
                            // if we acquired a transaction and a failure occurred, then simply close the transaction
                            if ( !isPeriodicCommit )
                            {
                                if ( failed )
                                {
                                    closeTransaction( ctx, false );
                                }
                            }
                            else
                            {
                                ctx.currentTransaction = spi.beginTransaction( ctx.loginContext );
                            }
                        }
                    }

                    @Override
                    void streamResult( MutableTransactionState ctx, ThrowingConsumer<BoltResult,Exception> resultConsumer ) throws Exception
                    {
                        assert ctx.currentResult != null;

                        boolean success = false;
                        try
                        {
                            success = consumeResult( ctx, resultConsumer );
                        }
                        finally
                        {
                            closeTransaction( ctx, success );
                        }
                    }

                    @Override
                    State commitTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException
                    {
                        throw new QueryExecutionKernelException( new InvalidSemanticsException( "No current transaction to commit." ) );
                    }

                    @Override
                    State rollbackTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi )
                    {
                        ctx.currentResult = BoltResult.EMPTY;
                        return AUTO_COMMIT;
                    }
                },
        EXPLICIT_TRANSACTION
                {
                    @Override
                    State beginTransaction( Bookmark bookmark, MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException
                    {
                        throw new QueryExecutionKernelException( new InvalidSemanticsException( "Nested transactions are not supported." ) );
                    }

                    @Override
                    State run( MutableTransactionState ctx, TransactionStateMachineSPI spi, String statement, MapValue params ) throws KernelException
                    {
                        if ( statement.isEmpty() )
                        {
                            statement = ctx.lastStatement;
                        }
                        else
                        {
                            ctx.lastStatement = statement;
                        }
                        if ( spi.isPeriodicCommit( statement ) )
                        {
                            throw new QueryExecutionKernelException( new InvalidSemanticsException(
                                    "Executing queries that use periodic commit in an " +
                                    "open transaction is not possible." ) );
                        }
                        else
                        {
                            BoltResultHandle resultHandle = spi.executeQuery( ctx.querySource, ctx.loginContext, statement, params );
                            startExecution( ctx, resultHandle );
                            return EXPLICIT_TRANSACTION;
                        }
                    }

                    @Override
                    void streamResult( MutableTransactionState ctx,
                            ThrowingConsumer<BoltResult,Exception> resultConsumer ) throws Exception
                    {
                        assert ctx.currentResult != null;
                        consumeResult( ctx, resultConsumer );
                    }

                    @Override
                    State commitTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException
                    {
                        closeTransaction( ctx, true );
                        long txId = spi.newestEncounteredTxId();
                        Bookmark bookmark = new Bookmark( txId );
                        ctx.currentResult = new BookmarkResult( bookmark );
                        return AUTO_COMMIT;
                    }

                    @Override
                    State rollbackTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException
                    {
                        closeTransaction( ctx, false );
                        ctx.currentResult = BoltResult.EMPTY;
                        return AUTO_COMMIT;
                    }
                };

        abstract State beginTransaction( Bookmark bookmark, MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException;

        abstract State run( MutableTransactionState ctx, TransactionStateMachineSPI spi, String statement, MapValue params ) throws KernelException;

        abstract void streamResult( MutableTransactionState ctx, ThrowingConsumer<BoltResult,Exception> resultConsumer ) throws Exception;

        abstract State commitTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException;

        abstract State rollbackTransaction( MutableTransactionState ctx, TransactionStateMachineSPI spi ) throws KernelException;

        void terminateQueryAndRollbackTransaction( MutableTransactionState ctx ) throws TransactionFailureException
        {
            if ( ctx.currentResultHandle != null )
            {
                ctx.currentResultHandle.terminate();
                ctx.currentResultHandle = null;
            }
            if ( ctx.currentResult != null )
            {
                ctx.currentResult.close();
                ctx.currentResult = null;
            }

           closeTransaction( ctx, false);
        }

        /*
         * This is overly careful about always closing and nulling the transaction since
         * reset can cause ctx.currentTransaction to be null we store in local variable.
         */
        void closeTransaction( MutableTransactionState ctx, boolean success ) throws TransactionFailureException
        {
            KernelTransaction tx = ctx.currentTransaction;
            ctx.currentTransaction = null;
            if ( tx != null )
            {
                try
                {
                    if ( success )
                    {
                        tx.success();
                    }
                    else
                    {
                        tx.failure();
                    }
                    if ( tx.isOpen() )
                    {
                        tx.close();
                    }
                }
                finally
                {
                    ctx.currentTransaction = null;
                }
            }
        }

        boolean consumeResult( MutableTransactionState ctx, ThrowingConsumer<BoltResult,Exception> resultConsumer ) throws Exception
        {
            boolean success = false;
            try
            {
                resultConsumer.accept( ctx.currentResult );
                success = true;
            }
            finally
            {
                ctx.currentResult.close();
                ctx.currentResult = null;

                if ( ctx.currentResultHandle != null )
                {
                    ctx.currentResultHandle.close( success );
                    ctx.currentResultHandle = null;
                }
            }
            return success;
        }

        void startExecution( MutableTransactionState ctx, BoltResultHandle resultHandle ) throws KernelException
        {
            ctx.currentResultHandle = resultHandle;
            try
            {
                ctx.currentResult = resultHandle.start();
            }
            catch ( Throwable t )
            {
                ctx.currentResultHandle.close( false );
                ctx.currentResultHandle = null;
                throw t;
            }
        }

    }

    static class MutableTransactionState
    {
        /** The current session security context to be used for starting transactions */
        final LoginContext loginContext;

        /** The current transaction, if present */
        KernelTransaction currentTransaction;

        Status pendingTerminationNotice;

        /** Last Cypher statement executed */
        String lastStatement = "";

        /** The current pending result, if present */
        BoltResult currentResult;

        final Clock clock;

        /** A re-usable statement metadata instance that always represents the currently running statement */
        private final StatementMetadata currentStatementMetadata = new StatementMetadata()
        {
            @Override
            public String[] fieldNames()
            {
                return currentResult.fieldNames();
            }
        };

        BoltQuerySource querySource;
        BoltResultHandle currentResultHandle;

        private MutableTransactionState( AuthenticationResult authenticationResult, Clock clock )
        {
            this.clock = clock;
            this.loginContext = authenticationResult.getLoginContext();
        }
    }
}
