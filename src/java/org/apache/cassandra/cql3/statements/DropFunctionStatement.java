/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Joiner;

import org.apache.cassandra.auth.FunctionResource;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.cql3.CQL3Type;
import org.apache.cassandra.cql3.functions.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.transport.Event;

/**
 * A <code>DROP FUNCTION</code> statement parsed from a CQL query.
 */
public final class DropFunctionStatement extends SchemaAlteringStatement
{
    private FunctionName functionName;
    private final boolean ifExists;
    private final List<CQL3Type.Raw> argRawTypes;
    private final boolean argsPresent;

    private Function old;
    private List<AbstractType<?>> argTypes;

    public DropFunctionStatement(FunctionName functionName,
                                 List<CQL3Type.Raw> argRawTypes,
                                 boolean argsPresent,
                                 boolean ifExists)
    {
        this.functionName = functionName;
        this.argRawTypes = argRawTypes;
        this.argsPresent = argsPresent;
        this.ifExists = ifExists;
    }

    @Override
    public Prepared prepare() throws InvalidRequestException
    {
        argTypes = new ArrayList<>(argRawTypes.size());
        for (CQL3Type.Raw rawType : argRawTypes)
        {
            if (rawType.isFrozen())
                throw new InvalidRequestException("The function arguments should not be frozen; remove the frozen<> modifier");

            // UDT are not supported non frozen but we do not allow the frozen keyword for argument. So for the moment we
            // freeze them here
            if (!rawType.canBeNonFrozen())
                rawType.freeze();

            argTypes.add(rawType.prepare(functionName.keyspace).getType());
        }
        return super.prepare();
    }

    @Override
    public void prepareKeyspace(ClientState state) throws InvalidRequestException
    {
        if (!functionName.hasKeyspace() && state.getRawKeyspace() != null)
            functionName = new FunctionName(state.getKeyspace(), functionName.name);

        if (!functionName.hasKeyspace())
            throw new InvalidRequestException("Functions must be fully qualified with a keyspace name if a keyspace is not set for the session");

        ThriftValidation.validateKeyspaceNotSystem(functionName.keyspace);
    }

    @Override
    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        Function function = findFunction();
        if (function == null)
        {
            if (!ifExists)
                throw new InvalidRequestException(String.format("Unconfigured function %s.%s(%s)",
                                                                functionName.keyspace,
                                                                functionName.name,
                                                                Joiner.on(",").join(argRawTypes)));
        }
        else
        {
            state.ensureHasPermission(Permission.DROP, FunctionResource.function(function.name().keyspace,
                                                                                 function.name().name,
                                                                                 function.argTypes()));
        }
    }

    @Override
    public void validate(ClientState state)
    {
        List<Function> olds = Functions.find(functionName);

        if (!argsPresent && olds != null && olds.size() > 1)
            throw new InvalidRequestException(String.format("'DROP FUNCTION %s' matches multiple function definitions; " +
                                                            "specify the argument types by issuing a statement like " +
                                                            "'DROP FUNCTION %s (type, type, ...)'. Hint: use cqlsh " +
                                                            "'DESCRIBE FUNCTION %s' command to find all overloads",
                                                            functionName, functionName, functionName));
    }

    @Override
    public Event.SchemaChange changeEvent()
    {
        return new Event.SchemaChange(Event.SchemaChange.Change.DROPPED, Event.SchemaChange.Target.FUNCTION,
                                      old.name().keyspace, old.name().name, AbstractType.asCQLTypeStringList(old.argTypes()));
    }

    @Override
    public boolean announceMigration(boolean isLocalOnly) throws RequestValidationException
    {
        old = findFunction();
        if (old == null)
        {
            if (ifExists)
                return false;
            else
                throw new InvalidRequestException(getMissingFunctionError());
        }

        List<Function> references = Functions.getReferencesTo(old);
        if (!references.isEmpty())
            throw new InvalidRequestException(String.format("Function '%s' still referenced by %s", old, references));

        MigrationManager.announceFunctionDrop((UDFunction) old, isLocalOnly);

        return true;
    }

    private String getMissingFunctionError()
    {
        // just build a nicer error message
        StringBuilder sb = new StringBuilder("Cannot drop non existing function '");
        sb.append(functionName);
        if (argsPresent)
            sb.append(Joiner.on(", ").join(argRawTypes));
        sb.append("'");
        return sb.toString();
    }

    private Function findFunction()
    {
        Function old;
        if (argsPresent)
        {
            old = Functions.find(functionName, argTypes);
            if (old == null || !(old instanceof ScalarFunction))
            {
                return null;
            }
        }
        else
        {
            List<Function> olds = Functions.find(functionName);
            if (olds == null || olds.isEmpty() || !(olds.get(0) instanceof ScalarFunction))
                return null;

            old = olds.get(0);
        }
        return old;
    }
}
