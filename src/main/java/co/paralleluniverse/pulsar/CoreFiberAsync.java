/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberAsync;

/**
 *
 * @author pron
 */
public class CoreFiberAsync extends FiberAsync<Object, IFn, Void, Exception> {
    private final IFn callback;

    public CoreFiberAsync(IFn callback) {
        this.callback = callback;
    }
    
    public CoreFiberAsync(IFn callback, boolean immediate) {
        super(immediate);
        this.callback = callback;
    }
    
    
    @Override
    protected IFn getCallback() {
        return callback;
    }

    
    @Override
    protected Void requestAsync(Fiber fiber, IFn clbck) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
}
