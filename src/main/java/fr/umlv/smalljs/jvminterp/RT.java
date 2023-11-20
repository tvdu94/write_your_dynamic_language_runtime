package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.invoker;
import static java.lang.invoke.MethodType.genericMethodType;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import fr.umlv.smalljs.rt.ArrayMap;
import fr.umlv.smalljs.rt.ArrayMap.Layout;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class RT {
  private static final MethodHandle INVOKER, LOOKUP, REGISTER, TRUTH, GET_MH, METH_LOOKUP_MH;
  static {
    var lookup = MethodHandles.lookup();
    try {
      INVOKER = lookup.findVirtual(JSObject.class, "invoke", methodType(Object.class, Object.class, Object[].class));
      LOOKUP = lookup.findVirtual(JSObject.class, "lookup", methodType(Object.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));
      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      GET_MH = lookup.findVirtual(JSObject.class, "getMethodHandle", methodType(MethodHandle.class));
      METH_LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
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

//  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
//    var invType = genericMethodType(type.parameterCount()-1);
//    MethodHandle exactInvoker =MethodHandles.dropArguments(MethodHandles.invoker(invType),1, Object.class);
//    MethodHandle getMH = GET_MH.asType(methodType(MethodHandle.class,Object.class));
//    MethodHandle target = MethodHandles.foldArguments(exactInvoker,getMH);
//    return new ConstantCallSite(target);
//  }
public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
  return new InliningCache(type);
}

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, POINTER_CHECK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        POINTER_CHECK = lookup.findStatic(InliningCache.class, "pointerCheck", methodType(boolean.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    public InliningCache(MethodType type) {
      super(type);
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
    }

    private static boolean pointerCheck (Object o1, Object o2){
      return o1 == o2;
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject)qualifier;
      //System.out.println("Slow path "+qualifier);

      var mh = jsObject.getMethodHandle();
      var varargs = mh.isVarargsCollector();
      mh = dropArguments(mh, 0,Object.class); //drop qualifier
      if (varargs){
        mh = mh.asVarargsCollector(Object[].class);
      }

      if (!varargs && !mh.type().equals(type())){
       throw new Failure("wrong number of arguments expected "+ mh.type().parameterCount() + " but found "+ type().parameterCount());
      }

      var target = mh.asType(type());
      var test = POINTER_CHECK.bindTo(qualifier);
      MethodHandle guard = guardWithTest(test, target, new InliningCache(type()).dynamicInvoker() );
      setTarget(guard);
      return mh.asType(type());

    }
  }


/*
  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    return new ConstantCallSite(insertArguments(LOOKUP, 1, fieldName).asType(type));
  }*/

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
    return new ConstantCallSite(insertArguments(REGISTER, 1, fieldName).asType(type));
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject)receiver.lookup(fieldName);
    return function.getMethodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
    var combiner = MethodHandles.insertArguments(METH_LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
    var target = MethodHandles.foldArguments(invoker(type), combiner);
    return new ConstantCallSite(target);
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String functionName) {
    //throw new UnsupportedOperationException("TODO bsm_lookup");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var target = MethodHandles.insertArguments(LOOKUP, 0, globalEnv, functionName);
    return new ConstantCallSite(target);
  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
    //throw new UnsupportedOperationException("TODO bsm_fun");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var fun = classLoader.getDictionary().lookupAndClear(funId);
    return ByteCodeRewriter.createFunction(fun.name().orElse("lambda"), fun.parameters(), fun.body(), globalEnv);
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
//    throw new UnsupportedOperationException("TODO bsm_register");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var target = insertArguments(REGISTER,0, globalEnv, functionName);
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }
  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
    //throw new UnsupportedOperationException("TODO bsm_truth");
    return new ConstantCallSite(TRUTH);
  }
  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    //return new ConstantCallSite(insertArguments(LOOKUP, 1, fieldName).asType(type));
    return new InliningFieldCache(type, fieldName);
  }

  private static class InliningFieldCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, LAYOUT_CHECK, FAST_ACCESS;
    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningFieldCache.class, "slowPath", methodType(Object.class, Object.class));
        LAYOUT_CHECK = lookup.findStatic(InliningFieldCache.class, "layoutCheck", methodType(boolean.class, ArrayMap.Layout.class, Object.class));
        FAST_ACCESS = lookup.findVirtual(JSObject.class, "fastAccess", methodType(Object.class, int.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final String fieldName;

    public InliningFieldCache(MethodType type, String fieldName) {
      super(type);
      this.fieldName = fieldName;
      setTarget(SLOW_PATH.bindTo(this));
    }

    @SuppressWarnings("unused")  // called by a MH
    private static boolean layoutCheck(ArrayMap.Layout layout, Object o) {
      return layout == ((JSObject)o).getLayout();
    }

    @SuppressWarnings("unused")  // called by a MH
    private Object slowPath(Object receiver) {
      var jsObject = (JSObject)receiver;

      // classical access to the value
      //var value = jsObject.lookup(fieldName);

      // fast access
      var layout = jsObject.getLayout();
      var slot = layout.slot(fieldName);   // may be -1 !
      if (slot == -1){
        return UNDEFINED;
      }


      var value = jsObject.fastAccess(slot);


      var test = LAYOUT_CHECK.bindTo(layout);
      var target = insertArguments(FAST_ACCESS,1,slot).asType(methodType(Object.class,Object.class));
      var fallback = new InliningFieldCache(type(), fieldName).dynamicInvoker();
      MethodHandle guard = guardWithTest(test,target,fallback);
      setTarget(guard);

      return value;
    }
  }

}
