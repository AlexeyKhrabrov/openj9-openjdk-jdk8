/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.nashorn.internal.runtime;

import static jdk.nashorn.internal.runtime.ECMAErrors.referenceError;
import static jdk.nashorn.internal.runtime.linker.Lookup.MH;

import java.lang.invoke.MethodHandle;

import jdk.nashorn.internal.codegen.objects.ObjectClassGenerator;
import jdk.nashorn.internal.runtime.linker.Lookup;
import jdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import jdk.nashorn.internal.runtime.linker.NashornGuards;

import org.dynalang.dynalink.CallSiteDescriptor;
import org.dynalang.dynalink.linker.GuardedInvocation;

/**
 * Instances of this class are quite ephemeral; they only exist for the duration of an invocation of
 * {@link ScriptObject#findSetMethod(CallSiteDescriptor, org.dynalang.dynalink.linker.LinkRequest)} and
 * serve as the actual encapsulation of the algorithm for creating an appropriate property setter method.
 */
final class SetMethodCreator {
    // See constructor parameters for description of fields
    private final ScriptObject sobj;
    private final PropertyMap map;
    private final FindProperty find;
    private final CallSiteDescriptor desc;

    /**
     * Creates a new property setter method creator.
     * @param sobj the object for which we're creating the property setter
     * @param find a result of a {@link ScriptObject#findProperty(String, boolean)} on the object for the property we
     * want to create a setter for. Can be null if the property does not yet exist on the object.
     * @param desc the descriptor of the call site that triggered the property setter lookup
     */
    SetMethodCreator(final ScriptObject sobj, final FindProperty find, final CallSiteDescriptor desc) {
        this.sobj = sobj;
        this.map = sobj.getMap();
        this.find = find;
        this.desc = desc;
    }

    private String getName() {
        return desc.getNameToken(CallSiteDescriptor.NAME_OPERAND);
    }

    private PropertyMap getMap() {
        return map;
    }

    /**
     * Creates the actual guarded invocation that represents the dynamic setter method for the property.
     * @return the actual guarded invocation that represents the dynamic setter method for the property.
     */
    GuardedInvocation createGuardedInvocation() {
        return createSetMethod().createGuardedInvocation();
    }

    /**
     * This class encapsulates the results of looking up a setter method; it's basically a triple of a method hanle,
     * a Property object, and flags for invocation.
     *
     */
    private class SetMethod {
        private final MethodHandle methodHandle;
        private final Property property;

        /**
         * Creates a new lookup result.
         * @param methodHandle the actual method handle
         * @param property the property object. Can be null in case we're creating a new property in the global object.
         */
        SetMethod(final MethodHandle methodHandle, final Property property) {
            assert methodHandle != null;
            this.methodHandle = methodHandle;
            this.property = property;
        }

        /**
         * Composes from its components an actual guarded invocation that represents the dynamic setter method for the property.
         * @return the composed guarded invocation that represents the dynamic setter method for the property.
         */
        GuardedInvocation createGuardedInvocation() {
            return new GuardedInvocation(methodHandle, getGuard());
        }

        private MethodHandle getGuard() {
            return needsNoGuard() ? null : NashornGuards.getMapGuard(getMap());
        }

        private boolean needsNoGuard() {
            return NashornCallSiteDescriptor.isFastScope(desc) &&
                    (ObjectClassGenerator.OBJECT_FIELDS_ONLY || isPropertyTypeStable());
        }

        private boolean isPropertyTypeStable() {
            return property == null || !property.canChangeType();
        }
    }

    private SetMethod createSetMethod() {
        if (find != null) {
            return createExistingPropertySetter();
        }

        checkStrictCreateNewVariable();

        if (sobj.isScope()) {
            return createGlobalPropertySetter();
        }

        return createNewPropertySetter();
    }

    private void checkStrictCreateNewVariable() {
        // In strict mode, assignment can not create a new variable.
        // See also ECMA Annex C item 4. ReferenceError is thrown.
        if (NashornCallSiteDescriptor.isScope(desc) && NashornCallSiteDescriptor.isStrict(desc)) {
            referenceError("not.defined", getName());
        }
    }

    private SetMethod createExistingPropertySetter() {
        final Property property = find.getProperty();
        final Class<?> type = desc.getMethodType().parameterType(1);
        final MethodHandle methodHandle = find.getSetter(type, NashornCallSiteDescriptor.isStrict(desc));

        assert methodHandle != null;
        assert property     != null;

        final MethodHandle boundHandle;
        if (!property.hasSetterFunction() && find.isInherited()) {
            boundHandle = ScriptObject.bindTo(methodHandle, find.getOwner());
        } else {
            boundHandle = methodHandle;
        }
        return new SetMethod(boundHandle, property);
    }

    private SetMethod createGlobalPropertySetter() {
        final ScriptObject global = Context.getGlobalTrusted();
        return new SetMethod(ScriptObject.bindTo(global.addSpill(getName()), global), null);
    }

    private SetMethod createNewPropertySetter() {
        final int nextEmbed = sobj.findEmbed();
        final SetMethod sm;
        if (nextEmbed >= ScriptObject.EMBED_SIZE) {
            sm = createNewSpillPropertySetter();
        } else {
            sm = createNewEmbedPropertySetter(nextEmbed);
        }

        sobj.notifyPropertyAdded(sobj, sm.property);
        return sm;
    }

    private SetMethod createNewSpillPropertySetter() {
        final int nextSpill = getMap().getSpillLength();

        final Property property = createSpillProperty(nextSpill);
        return new SetMethod(createSpillMethodHandle(nextSpill, property), property);
    }

    private Property createSpillProperty(final int nextSpill) {
        final MethodHandle getter = MH.asType(MH.insertArguments(MH.arrayElementGetter(Object[].class), 1, nextSpill), Lookup.GET_OBJECT_TYPE);
        final MethodHandle setter = MH.asType(MH.insertArguments(MH.arrayElementSetter(Object[].class), 1, nextSpill), Lookup.SET_OBJECT_TYPE);

        return new SpillProperty(getName(), Property.IS_SPILL, nextSpill, getter, setter);
    }

    private MethodHandle createSpillMethodHandle(final int nextSpill, Property property) {
        final PropertyMap oldMap = getMap();
        final PropertyMap newMap = getNewMap(property);

        final Object[] spill = sobj.spill;
        if (spill == null) {
            return MH.insertArguments(ScriptObject.SETSPILLWITHNEW,  0, desc, oldMap, newMap, nextSpill);
        } else if (nextSpill < spill.length) {
            return MH.insertArguments(ScriptObject.SETSPILL,         0, desc, oldMap, newMap, nextSpill);
        } else {
            final int newLength = (nextSpill + ScriptObject.SPILL_RATE) / ScriptObject.SPILL_RATE * ScriptObject.SPILL_RATE;
            return MH.insertArguments(ScriptObject.SETSPILLWITHGROW, 0, desc, oldMap, newMap, nextSpill, newLength);
        }
    }

    private SetMethod createNewEmbedPropertySetter(final int nextEmbed) {
        sobj.useEmbed(nextEmbed);
        final Property property = new SpillProperty(getName(), 0, nextEmbed, ScriptObject.GET_EMBED[nextEmbed], ScriptObject.SET_EMBED[nextEmbed]);
        //TODO specfields
        final MethodHandle methodHandle = MH.insertArguments(ScriptObject.SETEMBED, 0, desc, getMap(), getNewMap(property), property.getSetter(Object.class, getMap()), nextEmbed);
        return new SetMethod(methodHandle, property);
    }

    private PropertyMap getNewMap(Property property) {
        return getMap().addProperty(property);
    }
}
