/*
 * Copyright (c) 2013, 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.hotspot.tools.ctw;

import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import jdk.internal.reflect.ConstantPool;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Provide method to compile whole class.
 * Also contains compiled methods and classes counters.
 */
public class Compiler {

    // Call GC after compiling as many methods. This would remove the stale methods.
    // This threshold should balance the GC overhead and the cost of keeping lots
    // of stale methods around.
    private static final long GC_METHOD_THRESHOLD = Long.getLong("gcMethodThreshold", 100);

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final WhiteBox WHITE_BOX = WhiteBox.getWhiteBox();
    private static final AtomicLong METHOD_COUNT = new AtomicLong();
    private static final AtomicLong METHODS_SINCE_LAST_GC = new AtomicLong();

    private Compiler() { }

    /**
     * @return count of processed methods
     */
    public static long getMethodCount() {
        return METHOD_COUNT.get();
    }

    /**
     * Compiles all methods and constructors.
     *
     * @param aClass class to compile
     * @param id an id of the class
     * @param executor executor used for compile task invocation
     * @throws NullPointerException if {@code class} or {@code executor}
     *                              is {@code null}
     */
    public static void compileClass(Class<?> aClass, long id, Executor executor) {
        Objects.requireNonNull(aClass);
        Objects.requireNonNull(executor);

        // Initialize all constant pool entries, if requested.
        if (Utils.COMPILE_THE_WORLD_PRELOAD_CLASSES) {
            ConstantPool constantPool = SharedSecrets.getJavaLangAccess().getConstantPool(aClass);
            preloadClasses(aClass.getName(), id, constantPool);
        }

        // Attempt to initialize the class. If initialization is not possible
        // due to NCDFE, accept this, and try compile anyway.
        try {
            UNSAFE.ensureClassInitialized(aClass);
        } catch (NoClassDefFoundError e) {
            CompileTheWorld.OUT.printf("[%d]\t%s\tNOTE unable to init class : %s%n",
                id, aClass.getName(), e);
        }
        compileClinit(aClass, id);

        // Getting constructor/methods with unresolvable signatures would fail with NCDFE.
        // Try to get as much as possible, and compile everything else.
        // TODO: Would be good to have a Whitebox method that returns the subset of resolvable
        // constructors/methods without throwing NCDFE. This would extend the testing scope.
        Constructor[] constructors = new Constructor[0];
        Method[] methods = new Method[0];

        try {
            constructors = aClass.getDeclaredConstructors();
        } catch (NoClassDefFoundError e) {
            CompileTheWorld.OUT.printf("[%d]\t%s\tNOTE unable to get constructors : %s%n",
                id, aClass.getName(), e);
        }

        try {
            methods = aClass.getDeclaredMethods();
        } catch (NoClassDefFoundError e) {
            CompileTheWorld.OUT.printf("[%d]\t%s\tNOTE unable to get methods : %s%n",
                id, aClass.getName(), e);
        }

        // Populate profile for all methods to expand the scope of
        // compiler optimizations. Do this before compilations start.
        for (Executable e : constructors) {
            WHITE_BOX.markMethodProfiled(e);
        }
        for (Executable e : methods) {
            WHITE_BOX.markMethodProfiled(e);
        }

        // Now schedule the compilations.
        long methodCount = 0;
        for (Executable e : constructors) {
            ++methodCount;
            executor.execute(new CompileMethodCommand(id, e));
        }
        for (Executable e : methods) {
            ++methodCount;
            executor.execute(new CompileMethodCommand(id, e));
        }
        METHOD_COUNT.addAndGet(methodCount);

        // See if we need to schedule a GC
        while (true) {
            long current = METHODS_SINCE_LAST_GC.get();
            long update = current + methodCount;
            if (update >= GC_METHOD_THRESHOLD) {
                update = 0;
            }
            if (METHODS_SINCE_LAST_GC.compareAndSet(current, update)) {
                if (update == 0) {
                    executor.execute(() -> System.gc());
                }
                break;
            }
        }
    }

    private static void preloadClasses(String className, long id,
            ConstantPool constantPool) {
        for (int i = 0, n = constantPool.getSize(); i < n; ++i) {
            try {
                if (constantPool.getTagAt(i) == ConstantPool.Tag.CLASS) {
                    constantPool.getClassAt(i);
                }
            } catch (NoClassDefFoundError e) {
                CompileTheWorld.OUT.printf("[%d]\t%s\tNOTE unable to preload : %s%n",
                    id, className, e);
            } catch (Throwable t) {
                CompileTheWorld.OUT.printf("[%d]\t%s\tWARNING preloading failed : %s%n",
                    id, className, t);
                t.printStackTrace(CompileTheWorld.ERR);
            }
        }
    }

    private static void compileClinit(Class<?> aClass, long id) {
        int startLevel = Utils.INITIAL_COMP_LEVEL;
        int endLevel = Utils.TIERED_COMPILATION ? Utils.TIERED_STOP_AT_LEVEL : startLevel;
        for (int i = startLevel; i <= endLevel; ++i) {
            try {
                WHITE_BOX.enqueueInitializerForCompilation(aClass, i);
            } catch (Throwable t) {
                CompileTheWorld.OUT.println(String.format("[%d]\t%s::<clinit>\tERROR at level %d : %s",
                        id, aClass.getName(), i, t));
                t.printStackTrace(CompileTheWorld.ERR);
            }
        }
    }

    /**
     * Compilation of method.
     * Will compile method on all available comp levels.
     */
    private static class CompileMethodCommand implements Runnable {
        private final long classId;
        private final String className;
        private final Executable method;

        /**
         * @param classId   id of class
         * @param method    compiled for compilation
         */
        public CompileMethodCommand(long classId, Executable method) {
            this.classId = classId;
            this.className = method.getDeclaringClass().getName();
            this.method = method;
        }

        @Override
        public final void run() {
            // Make sure method is not compiled at any level before starting
            // progressive compilations. No deopt in-between tiers is needed,
            // as long as we increase the compilation levels one by one.
            WHITE_BOX.deoptimizeMethod(method);

            int compLevel = Utils.INITIAL_COMP_LEVEL;
            if (Utils.TIERED_COMPILATION) {
                for (int i = compLevel; i <= Utils.TIERED_STOP_AT_LEVEL; ++i) {
                    compileAtLevel(i);
                }
            } else {
                compileAtLevel(compLevel);
            }

            // Ditch all the compiled versions of the code, make the method
            // eligible for sweeping sooner.
            WHITE_BOX.deoptimizeMethod(method);
        }

        private void waitCompilation() {
            if (!Utils.BACKGROUND_COMPILATION) {
                return;
            }
            final Object obj = new Object();
            synchronized (obj) {
                for (int i = 0;
                     i < 10 && WHITE_BOX.isMethodQueuedForCompilation(method);
                     ++i) {
                    try {
                        obj.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void compileAtLevel(int compLevel) {
            if (WHITE_BOX.isMethodCompilable(method, compLevel)) {
                try {
                    WHITE_BOX.enqueueMethodForCompilation(method, compLevel);
                    waitCompilation();
                    int tmp = WHITE_BOX.getMethodCompilationLevel(method);
                    if (tmp != compLevel) {
                        log("WARNING compilation level = " + tmp
                                + ", but not " + compLevel);
                    } else if (Utils.IS_VERBOSE) {
                        log("compilation level = " + tmp + ". OK");
                    }
                } catch (Throwable t) {
                    log("ERROR at level " + compLevel);
                    t.printStackTrace(CompileTheWorld.ERR);
                }
            } else if (Utils.IS_VERBOSE) {
                log("not compilable at " + compLevel);
            }
        }

        private String methodName() {
            return String.format("%s::%s(%s)",
                    className,
                    method.getName(),
                    Arrays.stream(method.getParameterTypes())
                          .map(Class::getName)
                          .collect(Collectors.joining(", ")));
        }

        private void log(String message) {
            StringBuilder builder = new StringBuilder("[")
                    .append(classId)
                    .append("]\t")
                    .append(methodName());
            if (message != null) {
                builder.append('\t')
                       .append(message);
            }
            CompileTheWorld.ERR.println(builder);
        }
    }

}
