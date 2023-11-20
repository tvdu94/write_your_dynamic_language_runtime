package fr.umlv.smalljs.stackinterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static fr.umlv.smalljs.stackinterp.Instructions.CONST;
import static fr.umlv.smalljs.stackinterp.Instructions.DUP;
import static fr.umlv.smalljs.stackinterp.Instructions.FUNCALL;
import static fr.umlv.smalljs.stackinterp.Instructions.GOTO;
import static fr.umlv.smalljs.stackinterp.Instructions.JUMP_IF_FALSE;
import static fr.umlv.smalljs.stackinterp.Instructions.LOAD;
import static fr.umlv.smalljs.stackinterp.Instructions.LOOKUP;
import static fr.umlv.smalljs.stackinterp.Instructions.POP;
import static fr.umlv.smalljs.stackinterp.Instructions.REGISTER;
import static fr.umlv.smalljs.stackinterp.Instructions.RET;
import static fr.umlv.smalljs.stackinterp.Instructions.*;
import static fr.umlv.smalljs.stackinterp.TagValues.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Instr;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class InstrRewriter {
	static class InstrBuffer {
		private int[] instrs;
		private int size;

		InstrBuffer() {
			instrs = new int[32];
		}

		InstrBuffer emit(int value) {
			if (size == instrs.length) {
				instrs = Arrays.copyOf(instrs, size << 1);
			}
			instrs[size++] = value;
			return this;
		}

		int label() {
			return size;
		}

		int placeholder() {
			return size++;
		}

		void patch(int position, int label) {
			instrs[position] = label;
		}

		int[] toInstrs() {
			return Arrays.copyOf(instrs, size);
		}
	}

	public static JSObject createFunction(Optional<String> name, List<String> parameters, Block body, Dictionary dict, JSObject globalEnv) {
		var env = JSObject.newEnv(null);

		env.register("this", 0);
		for (var parameter : parameters) {
			env.register(parameter, env.length());
		}
		visitVariable(body, env);

		var buffer = new InstrBuffer();
		visit(body, env, buffer, dict, globalEnv);
		buffer.emit(CONST).emit(encodeDictObject(UNDEFINED, dict));
		buffer.emit(RET);

		var instrs = buffer.toInstrs();
		Instructions.dump(instrs, dict);

		var code = new Code(instrs, parameters.size() + 1 /* this */, env.length());
		var function = JSObject.newFunction(name.orElse("lambda"), (self, receiver, args) -> {
			if (receiver != UNDEFINED || args.length != 0) {
				throw new Failure("can not interpret a function with a receiver and/or arguments");
			}
			return StackInterpreter.execute(self, dict, globalEnv);
		});
		function.register("__code__", code);
		return function;
	}

	private static void visitVariable(Expr expression, JSObject env) {
		switch (expression) {
			case Block(List<Expr> instrs, int lineNumber) -> {
				for (Expr instr : instrs) {
					visitVariable(instr, env);
				}
			}
			case Literal<?>(Object value, int lineNumber) -> {
				// do nothing
			}
			case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
				// do nothing
			}
			case LocalVarAccess(String name, int lineNumber) -> {
				// do nothing
			}
			case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
				if (declaration) {
					env.register(name, env.length());
				}
			}
			case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
				// do nothing
			}
			case Return(Expr expr, int lineNumber) -> {
				// do nothing
			}
			case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
				visitVariable(trueBlock, env);
				visitVariable(falseBlock, env);
			}
			case New(Map<String, Expr> initMap, int lineNumber) -> {
				// do nothing
			}
			case FieldAccess(Expr receiver, String name, int lineNumber) -> {
				// do nothing
			}
			case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
				// do nothing
			}
			case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
				// do nothing
			}
		};
	}

	private static void visit(Expr expression, JSObject env, InstrBuffer buffer, Dictionary dict, JSObject globalEnv) {
		switch (expression) {
			case Block(List<Expr> instrs, int lineNumber) -> {
				// for each expression of the block
				for (var instr : instrs) {
					// visit the expression
					visit(instr, env, buffer, dict, globalEnv);
					// if the expression is an instruction (i.e. return void)
					if (!(instr instanceof Instr)) {
						// ask to top the top of the stack
						buffer.emit(POP);
					}
				}
			}
			case Literal<?>(Object literalValue, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Literal");
//				 test if the literal value is a positive integers
				if (literalValue instanceof Integer value && value >= 0) {
//				 emit a small int
				buffer.emit(CONST).emit(encodeSmallInt(value));
				} else {
//				 emit a dictionary object
				buffer.emit(CONST).emit(encodeDictObject(literalValue,dict));
				}
			}
			case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO FunCall");
				// visit the qualifier
				visit(qualifier,env,buffer,dict,globalEnv);
				// emit undefined
				buffer.emit(CONST).emit(encodeDictObject(UNDEFINED,dict));
				// visit all arguments
				for (var arg : args) {
					visit(arg,env,buffer,dict,globalEnv);
				}
				// emit the funcall
				buffer.emit(FUNCALL).emit(args.size());
			}
			case LocalVarAccess(String name, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO LocalVarAccess");
				// get the local variable name

				// find if there is a local variable in the environment with the name
				var slotOrUndefined = env.lookup(name);
				if (slotOrUndefined == UNDEFINED) {
				// emit a lookup with the name
				buffer.emit(LOOKUP).emit(encodeDictObject(name,dict));
				} else {
				// load the local variable with the slot
				buffer.emit(LOAD).emit((int)slotOrUndefined);
				}
			}
			case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO LocalVarAssignment");
				// visit the expression
				 visit(expr,env,buffer,dict,globalEnv);
				// find if there is a local variable in the env from the name
				var slotOrUndefined = env.lookup(name);
				if (slotOrUndefined == UNDEFINED) {
					throw new Failure("unknown local variable " + name);
				}
				// emit a store at the variable slot
				buffer.emit(STORE).emit((int) slotOrUndefined);
			}
			case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Fun");
				// create a JSObject function
				var function = createFunction(optName, parameters, body, dict, globalEnv);
				// emit a const on the function
				buffer.emit(CONST).emit(encodeAnyValue(function,dict));
				// if the name is present emit a code to register the function in the global environment
				optName.ifPresent(name -> {
				buffer.emit(DUP);
				buffer.emit(REGISTER).emit(encodeAnyValue(name,dict));
				});
			}
			case Return(Expr expr, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Return");
				// emit a visit of the expression
				visit(expr,env,buffer,dict,globalEnv);
				// emit a RET

				buffer.emit(RET);
			}
			case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO If");
				// visit the condition
				visit(condition,env,buffer,dict,globalEnv);
				// emit a JUMP_IF_FALSE and a placeholder
				var falsePlaceHolder = buffer.emit(JUMP_IF_FALSE).placeholder();
				// visit the true block
				visit(trueBlock,env,buffer,dict,globalEnv);
				// emit a goto with another placeholder
				var endPlaceHolder = buffer.emit(GOTO).placeholder();
				// patch the first placeholder
				buffer.patch(falsePlaceHolder, buffer.label());
				// visit the false block
				visit(falseBlock,env,buffer,dict,globalEnv);
				// patch the second placeholder
				buffer.patch(endPlaceHolder, buffer.label());
			}
			case New(Map<String, Expr> initMap, int lineNumber) -> {
//				throw new UnsupportedOperationException("TODO New");
				// create a JSObject class
				var clazz = JSObject.newObject(null);
				// loop over all the field initializations
				initMap.forEach((fieldName, expr) -> {
				//  register the field name with the right slot
				  clazz.register(fieldName,(int)env.lookup(fieldName));
				//   visit the initialization expression
				  visit(expr,env,buffer,dict,globalEnv);
				});
				// emit a NEW with the class
				buffer.emit(NEW).emit(encodeAnyValue(clazz,dict));
			}
			case FieldAccess(Expr receiver, String name, int lineNumber) -> {
				throw new UnsupportedOperationException("TODO FieldAccess");
				// visit the receiver
				//visit(...);
				// emit a GET with the field name
				//buffer.emit(...).emit(...);
			}
			case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
				throw new UnsupportedOperationException("TODO FieldAssignment");
				// visit the receiver
				//visit(...);
				// visit the expression
				//visit(...);
				// emit a PUT with the field name
				//buffer.emit(...).emit(...);
			}
			case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
				throw new UnsupportedOperationException("TODO MethodCall");
				// visit the receiver
				//visit(...);
				// emit a DUP, get the field name and emit a SWAP of the qualifier and the receiver
				//buffer.emit(DUP);
				//buffer.emit(...).emit(...);
				//buffer.emit(SWAP);
				// visit all arguments
				//for (var arg : args) {
				  //visit(...);
				//}
				// emit the funcall
				//buffer.emit(...).emit(...);
			}
		}
	}
}
