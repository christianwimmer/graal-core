/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot;

import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.DESTROYS_REGISTERS;
import static jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.runtime;

import java.util.Set;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CallingConvention.Type;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.HotSpotForeignCallTarget;
import jdk.vm.ci.hotspot.HotSpotProxified;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LocationIdentity;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.spi.ForeignCallDescriptor;
import com.oracle.graal.compiler.target.Backend;
import com.oracle.graal.hotspot.meta.HotSpotForeignCallsProvider;
import com.oracle.graal.hotspot.stubs.Stub;
import com.oracle.graal.word.WordTypes;

/**
 * The details required to link a HotSpot runtime or stub call.
 */
public class HotSpotForeignCallLinkageImpl extends HotSpotForeignCallTarget implements HotSpotForeignCallLinkage, HotSpotProxified {

    /**
     * The descriptor of the call.
     */
    protected final ForeignCallDescriptor descriptor;

    /**
     * Non-null (eventually) iff this is a call to a compiled {@linkplain Stub stub}.
     */
    private Stub stub;

    /**
     * The calling convention for this call.
     */
    private final CallingConvention outgoingCallingConvention;

    /**
     * The calling convention for incoming arguments to the stub, iff this call uses a compiled
     * {@linkplain Stub stub}.
     */
    private final CallingConvention incomingCallingConvention;

    private final RegisterEffect effect;

    private final Transition transition;

    /**
     * The registers and stack slots defined/killed by the call.
     */
    private Value[] temporaries = AllocatableValue.NONE;

    /**
     * The memory locations killed by the call.
     */
    private final LocationIdentity[] killedLocations;

    private final boolean reexecutable;

    /**
     * Creates a {@link HotSpotForeignCallLinkage}.
     *
     * @param descriptor the descriptor of the call
     * @param address the address of the code to call
     * @param effect specifies if the call destroys or preserves all registers (apart from
     *            temporaries which are always destroyed)
     * @param outgoingCcType outgoing (caller) calling convention type
     * @param incomingCcType incoming (callee) calling convention type (can be null)
     * @param transition specifies if this is a {@linkplain #needsDebugInfo() leaf} call
     * @param reexecutable specifies if the call can be re-executed without (meaningful) side
     *            effects. Deoptimization will not return to a point before a call that cannot be
     *            re-executed.
     * @param killedLocations the memory locations killed by the call
     */
    public static HotSpotForeignCallLinkage create(MetaAccessProvider metaAccess, CodeCacheProvider codeCache, WordTypes wordTypes, HotSpotForeignCallsProvider foreignCalls,
                    ForeignCallDescriptor descriptor, long address, RegisterEffect effect, Type outgoingCcType, Type incomingCcType, Transition transition, boolean reexecutable,
                    LocationIdentity... killedLocations) {
        CallingConvention outgoingCc = createCallingConvention(metaAccess, codeCache, wordTypes, descriptor, outgoingCcType);
        CallingConvention incomingCc = incomingCcType == null ? null : createCallingConvention(metaAccess, codeCache, wordTypes, descriptor, incomingCcType);
        HotSpotForeignCallLinkageImpl linkage = new HotSpotForeignCallLinkageImpl(descriptor, address, effect, transition, outgoingCc, incomingCc, reexecutable, killedLocations);
        if (outgoingCcType == HotSpotCallingConventionType.NativeCall) {
            linkage.temporaries = foreignCalls.getNativeABICallerSaveRegisters();
        }
        return linkage;
    }

    /**
     * Gets a calling convention for a given descriptor and call type.
     */
    public static CallingConvention createCallingConvention(MetaAccessProvider metaAccess, CodeCacheProvider codeCache, WordTypes wordTypes, ForeignCallDescriptor descriptor, Type ccType) {
        assert ccType != null;
        Class<?>[] argumentTypes = descriptor.getArgumentTypes();
        JavaType[] parameterTypes = new JavaType[argumentTypes.length];
        for (int i = 0; i < parameterTypes.length; ++i) {
            parameterTypes[i] = asJavaType(argumentTypes[i], metaAccess, wordTypes);
        }
        TargetDescription target = codeCache.getTarget();
        JavaType returnType = asJavaType(descriptor.getResultType(), metaAccess, wordTypes);
        RegisterConfig regConfig = codeCache.getRegisterConfig();
        return regConfig.getCallingConvention(ccType, returnType, parameterTypes, target);
    }

    private static JavaType asJavaType(Class<?> type, MetaAccessProvider metaAccess, WordTypes wordTypes) {
        ResolvedJavaType javaType = metaAccess.lookupJavaType(type);
        if (wordTypes.isWord(javaType)) {
            javaType = metaAccess.lookupJavaType(wordTypes.getWordKind().toJavaClass());
        }
        return javaType;
    }

    public HotSpotForeignCallLinkageImpl(ForeignCallDescriptor descriptor, long address, RegisterEffect effect, Transition transition, CallingConvention outgoingCallingConvention,
                    CallingConvention incomingCallingConvention, boolean reexecutable, LocationIdentity... killedLocations) {
        super(address);
        this.descriptor = descriptor;
        this.address = address;
        this.effect = effect;
        this.transition = transition;
        this.outgoingCallingConvention = outgoingCallingConvention;
        this.incomingCallingConvention = incomingCallingConvention;
        this.reexecutable = reexecutable;
        this.killedLocations = killedLocations;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(stub == null ? descriptor.toString() : stub.toString());
        sb.append("@0x").append(Long.toHexString(address)).append(':').append(outgoingCallingConvention).append(":").append(incomingCallingConvention);
        if (temporaries != null && temporaries.length != 0) {
            sb.append("; temps=");
            String sep = "";
            for (Value op : temporaries) {
                sb.append(sep).append(op);
                sep = ",";
            }
        }
        return sb.toString();
    }

    public boolean isReexecutable() {
        return reexecutable;
    }

    public boolean isGuaranteedSafepoint() {
        return transition == Transition.SAFEPOINT;
    }

    public LocationIdentity[] getKilledLocations() {
        return killedLocations;
    }

    public CallingConvention getOutgoingCallingConvention() {
        return outgoingCallingConvention;
    }

    public CallingConvention getIncomingCallingConvention() {
        return incomingCallingConvention;
    }

    public Value[] getTemporaries() {
        if (temporaries.length == 0) {
            return temporaries;
        }
        return temporaries.clone();
    }

    public long getMaxCallTargetOffset() {
        return runtime().getHostJVMCIBackend().getCodeCache().getMaxCallTargetOffset(address);
    }

    public ForeignCallDescriptor getDescriptor() {
        return descriptor;
    }

    public void setCompiledStub(Stub stub) {
        assert address == 0L : "cannot set stub for linkage that already has an address: " + this;
        this.stub = stub;
    }

    /**
     * Determines if this is a call to a compiled {@linkplain Stub stub}.
     */
    public boolean isCompiledStub() {
        return address == 0L || stub != null;
    }

    public void finalizeAddress(Backend backend) {
        if (address == 0) {
            assert stub != null : "linkage without an address must be a stub - forgot to register a Stub associated with " + descriptor + "?";
            InstalledCode code = stub.getCode(backend);

            Set<Register> destroyedRegisters = stub.getDestroyedCallerRegisters();
            if (!destroyedRegisters.isEmpty()) {
                AllocatableValue[] temporaryLocations = new AllocatableValue[destroyedRegisters.size()];
                int i = 0;
                for (Register reg : destroyedRegisters) {
                    temporaryLocations[i++] = reg.asValue();
                }
                temporaries = temporaryLocations;
            }
            address = code.getStart();
        }
    }

    public long getAddress() {
        assert address != 0L : "address not yet finalized: " + this;
        return address;
    }

    @Override
    public boolean destroysRegisters() {
        return effect == DESTROYS_REGISTERS;
    }

    @Override
    public boolean needsDebugInfo() {
        return transition == Transition.SAFEPOINT;
    }

    public boolean mayContainFP() {
        return transition != Transition.LEAF_NOFP;
    }

    public boolean needsJavaFrameAnchor() {
        if (transition == Transition.SAFEPOINT || transition == Transition.STACK_INSPECTABLE_LEAF) {
            if (stub != null) {
                // The stub will do the JavaFrameAnchor management
                // around the runtime call(s) it makes
                return false;
            } else {
                return true;
            }
        }
        return false;
    }

    public String getSymbol() {
        return stub == null ? null : stub.toString();
    }
}
