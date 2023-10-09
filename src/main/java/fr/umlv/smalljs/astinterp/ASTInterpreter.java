package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;
import fr.umlv.smalljs.rt.JSObject.Invoker;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static fr.umlv.smalljs.rt.JSObject.*;
import static java.util.stream.Collectors.joining;

public class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> instrs, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Block");
        // TODO loop over all instructions
        for (var instr : instrs){
          visit(instr,env);
        }
        yield UNDEFINED;
      }
      case Literal<?>(Object value, int lineNumber) -> {
        yield value;
      }
      case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var funMaybe = visit(qualifier,env);

        if (!(funMaybe instanceof JSObject jsObject)){
          throw new Failure("not a function "+funMaybe);

        }
          var values = args.stream().map(v -> visit(v,env)).toArray();
          yield jsObject.invoke(UNDEFINED,values);

      }
      case LocalVarAccess(String name, int lineNumber) -> {
        yield env.lookup(name);
      }
      case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        if (declaration && env.lookup(name) != UNDEFINED){
          throw new Failure("variable "+name+" already defined at "+lineNumber);
        }

        var value = visit(expr,env);
        env.register(name,value);
        yield UNDEFINED;
      }
      case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Fun");
        var functionName = optName.orElse("lambda");
        Invoker invoker = new Invoker() {
          @Override
          public Object invoke(JSObject self, Object receiver, Object... args) {
            if (args.length != parameters.size()){
              throw new Failure("wrong number of arguments "+lineNumber);
            }
            var env2 = newEnv(env);
            env2.register("this", receiver);
            for(var i = 0; i< args.length;i++){
              env2.register(parameters.get(i), args[i]);
            }
            try {
              return visit(body,env2);
            }
            catch (ReturnError returnError) {
              return returnError.getValue();
            }
          }
        };
        var function = JSObject.newFunction(functionName,invoker);
        optName.ifPresent(s -> env.register(s, function));
        yield function;
      }
      case Return(Expr expr, int lineNumber) -> {
        throw new ReturnError(visit(expr,env));
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        var val = visit(condition,env);
        if (! (val instanceof Integer intVal )){
          throw new Failure("pas boolean" + val);
        }
        if (intVal == 1){
          yield visit(trueBlock,env);
        }
        yield visit(falseBlock,env);
      }
      case New(Map<String, Expr> initMap, int  lineNumber) -> {
        var vals = JSObject.newObject(null);

        for (var key : initMap.keySet()){
          vals.register(key,visit(initMap.get(key),env));
        }
        yield vals;
      }

      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        var obj =visit(receiver,env);
        if (! (obj instanceof JSObject objtemp)){
          throw new Failure("not a object");
        }
        yield objtemp.lookup(name);
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        var obj =visit(receiver,env);
        if (! (obj instanceof JSObject objtemp)){
          throw new Failure("not a object");
        }
        objtemp.register(name,visit(expr,env));
        yield UNDEFINED;
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var obj =visit(receiver,env);
        if (! (obj instanceof JSObject objtemp)){
          throw new Failure("dzad not a object");
        }
        System.out.println(objtemp);
        var fun =  objtemp.lookup(name);
        if (! (fun instanceof JSObject objtemp2)){
          throw new Failure("dzad not a object");
        }
        System.out.println(objtemp2);

        var values = args.stream().map(v -> visit(v,env)).toArray();

        yield objtemp2.invoke(objtemp,values);
      }


    };
  }

  @SuppressWarnings("unchecked")
  public static void interpret(Script script, PrintStream outStream) {
    JSObject globalEnv = JSObject.newEnv(null);
    Block body = script.body();
    globalEnv.register("global", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] % (Integer) args[1]));

    globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    visit(body, globalEnv);
  }
}

