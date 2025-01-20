package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;

import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
  private static final MethodHandle LOOKUP, REGISTER, TRUTH, GET_MH, METH_LOOKUP_MH, PARAM_CHECK;
  static {
    var lookup = MethodHandles.lookup();
    try {
      LOOKUP = lookup.findVirtual(JSObject.class, "lookup", methodType(Object.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));
      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      GET_MH = lookup.findVirtual(JSObject.class, "getMethodHandle", methodType(MethodHandle.class));
      METH_LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
      PARAM_CHECK = lookup.findStatic(RT.class, "parameterCheck", methodType(MethodHandle.class, MethodHandle.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
    return UNDEFINED;
  }

  public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
    return constant;
  }

  private static MethodHandle parameterCheck(MethodHandle mh, int paramCount) {
    if (!mh.isVarargsCollector() && mh.type().parameterCount() != paramCount) {
      throw new Failure("Bro your param count is off; paramCount" + paramCount + "; mh.type.paramcount: " + mh.type().parameterCount());
    }
    return mh;
  }

  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    return new InliningCache(type, 1);
  }

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, POINTER_CHECK, UNOPTIMIZED_PATH;
    private static final int MAX_DEPTH = 3;
    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        POINTER_CHECK = lookup.findStatic(InliningCache.class, "pointerCheck", methodType(boolean.class, Object.class, JSObject.class));
        UNOPTIMIZED_PATH = lookup.findVirtual(InliningCache.class, "unoptimizedPath", methodType(MethodHandle.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final int depth;
    private InliningCache root;

    public InliningCache(MethodType type, int depth, InliningCache root) {
      super(type);
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
      this.depth = depth;
      this.root = root;
    }

    public InliningCache(MethodType type, int depth) {
      super(type);
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
      this.depth = depth;
      this.root = this;
    }

    private static boolean pointerCheck(Object qualifier, JSObject expectedQualifier) {
      return qualifier == expectedQualifier;
    }

    private MethodHandle unoptimizedPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject)qualifier;
      var mh = jsObject.getMethodHandle();

      parameterCheck(mh, type().parameterCount()-1);

        return MethodHandles.dropArguments(mh, 0, Object.class)
                .withVarargs(mh.isVarargsCollector())
                .asType(type());
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
        var jsObject = (JSObject)qualifier;
        var mh = jsObject.getMethodHandle();

        parameterCheck(mh, type().parameterCount()-1);


        var target = MethodHandles.dropArguments(mh, 0, Object.class)
                                  .withVarargs(mh.isVarargsCollector())
                                  .asType(type());

        if (depth >= MAX_DEPTH) {
          setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type()), UNOPTIMIZED_PATH.bindTo(this)));
          return target;
        }

        var pointerCheck = MethodHandles.insertArguments(POINTER_CHECK, 1, qualifier);
        if (root == null)
          root = this;
        var fallback = new InliningCache(type(), depth + 1, root).dynamicInvoker();

        var guard = MethodHandles.guardWithTest(
                pointerCheck,
                target,
                fallback);

        setTarget(guard);

        return target;
    }
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String functionName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    // get the LOOKUP method handle
    var target = LOOKUP;
    // use the global environment as first argument and the functionName as second argument
    target = MethodHandles.insertArguments(target, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var fun = classLoader.getDictionary().lookupAndClear(funId);
    return ByteCodeRewriter.createFunction(fun.optName().orElse("lambda"), fun.parameters(), fun.body(), globalEnv);
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    //get the REGISTER method handle
    var target = REGISTER;
    // use the global environment as first argument and the functionName as second argument
    target = MethodHandles.insertArguments(target, 0 , globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }
  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
    // get the TRUTH method handle
    var target = TRUTH;
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_get");
    // get the LOOKUP method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_set");
    // get the REGISTER method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject) receiver.lookup(fieldName);
    return function.getMethodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
    throw new UnsupportedOperationException("TODO bsm_methodcall");
    //var combiner = insertArguments(METH_LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
    //var target = foldArguments(invoker(type), combiner);
    //return new ConstantCallSite(target);
  }
}
