package fr.umlv.smalljs.stackinterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static fr.umlv.smalljs.stackinterp.TagValues.OBJECT_HEADER_SIZE;
import static fr.umlv.smalljs.stackinterp.TagValues.decodeAnyValue;
import static fr.umlv.smalljs.stackinterp.TagValues.decodeDictObject;
import static fr.umlv.smalljs.stackinterp.TagValues.decodeReference;
import static fr.umlv.smalljs.stackinterp.TagValues.encodeAnyValue;
import static fr.umlv.smalljs.stackinterp.TagValues.encodeDictObject;
import static fr.umlv.smalljs.stackinterp.TagValues.encodeReference;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class StackInterpreter {
	private static void push(int[] stack, int sp, int value) {
		stack[sp] = value;
	}

	private static int pop(int[] stack, int sp) {
		return stack[sp];
	}

	private static int peek(int[] stack, int sp) {
		return stack[sp - 1];
	}

	private static void store(int[] stack, int bp, int offset, int value) {
		stack[bp + offset] = value;
	}

	private static int load(int[] stack, int bp, int offset) {
		return stack[bp + offset];
	}

	private static void dumpStack(String message, int[] stack, int sp, int bp, Dictionary dict, int[] heap) {
		System.err.println(message);
		for (var i = sp - 1; i >= 0; i = i - 1) {
			var value = stack[i];
			try {
				System.err.println(((i == bp) ? "->" : "  ") + value + " " + decodeAnyValue(value, dict, heap));
			} catch (IndexOutOfBoundsException | ClassCastException e) {
				System.err.println(((i == bp) ? "->" : "  ") + value + " (can't decode)");
			}
		}
		System.err.println();
	}

	private static void dumpHeap(String message, int[] heap, int hp, Dictionary dict) {
		System.err.println(message);
		for (var i = 0; i < hp; i++) {
			var value = heap[i];
			try {
				System.err.println(i + ": " + value + " " + decodeAnyValue(value, dict, heap));
			} catch (IndexOutOfBoundsException | ClassCastException e) {
				System.err.println(i + ": " + value + " (can't decode)");
			}
		}
		System.err.println();
	}


	private static final int GC_OFFSET = 1;
	private static final int GC_MARK = -1;
	private static final int GC_EMPTY = -2;

	private static final int BP_OFFSET = 0;
	private static final int PC_OFFSET = 1;
	private static final int FUN_OFFSET = 2;
	private static final int ACTIVATION_SIZE = 3;

	private static final int RECEIVER_BASE_ARG_OFFSET = -1;
	private static final int QUALIFIER_BASE_ARG_OFFSET = -2;
	private static final int FUNCALL_PREFIX = 2;

	public static Object execute(JSObject function, Dictionary dict, JSObject globalEnv) {
		var stack = new int[96 /* 4096 */];
		var heap = new int[96 /* 4096 */];
		var code = (Code) function.lookup("__code__");
		var instrs = code.instrs();

		var undefined = encodeDictObject(UNDEFINED, dict);

		var hp = 0; // heap pointer
		var pc = 0; // instruction pointer
		var bp = 0; // base pointer
		var sp = bp + code.slotCount() + ACTIVATION_SIZE; // stack pointer

		// initialize all local variables
		for (var i = 0; i < code.slotCount(); i++) {
			stack[i] = undefined;
		}

		for (;;) {
			switch (instrs[pc++]) {
				case Instructions.CONST -> {
					//throw new UnsupportedOperationException("TODO CONST");
					// get the constant from the instruction to the stack
					var tagValue = instrs[pc++];
					// push(...)
					push(stack,sp++,tagValue);
				}
				case Instructions.LOOKUP -> {
					//throw new UnsupportedOperationException("TODO LOOKUP");
					// find the current instruction
					int indexTagValue = instrs [pc++];
					// decode the name from the instruction
					String name = (String)decodeDictObject(indexTagValue,dict);
					// lookup the name and push as any anyValue
					var value = globalEnv.lookup(name);
					var tagVal = encodeAnyValue(value,dict);
					push(stack,sp++,tagVal);

					dumpStack("in lookup", stack, sp, bp, dict, heap);
				}
				case Instructions.REGISTER -> {
					//throw new UnsupportedOperationException("TODO REGISTER");
					// find the current instruction
					int indexTagValue = instrs[pc++];
					// decode the name from the instructions
					String name = (String)decodeDictObject(indexTagValue,dict);
					// pop the value from the stack and decode it
					Object value = decodeAnyValue(pop(stack,--sp),dict,heap);
					// register it in the global environment
					 globalEnv.register(name,value);
				}
				case Instructions.LOAD -> {
					//throw new UnsupportedOperationException("TODO LOAD");
					// get local offset
					int offset = instrs[pc++];
					// load value from the local slots
					int value = load(stack,bp,offset);
					// push it to the top of the stack
					push(stack,sp++,value);
				}
				case Instructions.STORE -> {
					//throw new UnsupportedOperationException("TODO STORE");
					// get local offset
					int offset = instrs[pc++];
					// pop value from the stack
					var value = pop(stack,--sp);
					// store it in the local slots
					store(stack,bp,offset,value);
				}
				case Instructions.DUP -> {
					//throw new UnsupportedOperationException("TODO DUP");
					// get value on top of the stack
					var value = peek(stack,sp);
					// push it on top of the stack
					push(stack,sp++,value);
				}
				case Instructions.POP -> {
					// adjust the stack pointer
					--sp;
				}
				case Instructions.SWAP -> {
					//throw new UnsupportedOperationException("TODO SWAP");
					// pop first value from the stack
					var value1 = pop(stack,--sp);
					// pop second value from the stack
					var value2 = pop(stack,--sp);
					// push first value on top of the stack
					push(stack,sp++,value1);
					// push second value on top of the stack
          push(stack,sp++,value2);
				}
				case Instructions.FUNCALL -> {
					//throw new UnsupportedOperationException("TODO FUNCALL");
					// DEBUG
					dumpStack(">start funcall dump", stack, sp, bp, dict, heap);

					// find argument count
					var argumentCount = instrs[pc++];
					// find baseArg
					var baseArg = sp-argumentCount;
					// stack[baseArg] is the first argument
					// stack[baseArg + RECEIVER_BASE_ARG_OFFSET] is the receiver
					// stack[baseArg + QUALIFIER_BASE_ARG_OFFSET] is the qualifier (aka the function)

					// decode qualifier
					var funTagVal = stack[baseArg+QUALIFIER_BASE_ARG_OFFSET];
					var newFunction = (JSObject) decodeDictObject(funTagVal,dict);
					{ // DEBUG
						var receiver = decodeAnyValue(stack[baseArg + RECEIVER_BASE_ARG_OFFSET], dict, heap);
						var args = new Object[argumentCount];
						for (var i = 0; i < argumentCount; i++) {
							args[i] = decodeAnyValue(stack[baseArg + i], dict, heap);
						}
						System.err.println("funcall " + newFunction.getName() + " with " + receiver + " " + Arrays.toString(args));
					}

					// check if the function contains a code attribute
					var maybeCode = newFunction.lookup("__code__");
					if (maybeCode == UNDEFINED) { // native call !
					// decode receiver
					var receiver = decodeAnyValue(stack[baseArg + RECEIVER_BASE_ARG_OFFSET],dict,heap);

					  // decode arguments
					  var args = new Object[argumentCount];
					  for (var i = 0; i < argumentCount; i++) {
					  	args[i] = decodeAnyValue(stack[baseArg+i],dict,heap);
					  }

					   System.err.println("call native " + newFunction.getName() + " with " +
					   receiver + " " + java.util.Arrays.toString(args));

					  // call native function
					  var result = encodeAnyValue(newFunction.invoke(receiver, args), dict);

					  // fixup sp (receiver and function must be dropped)
					  sp = baseArg-2;

					  // push return value
					  push(stack,sp++,result);
					  continue;
					}
					//throw new UnsupportedOperationException("TODO FUNCALL");

					// initialize new code
					code = (Code) maybeCode;

					// check number of arguments
					if (code.parameterCount() != argumentCount + 1/* this */) {
						throw new Failure("wrong number of arguments for " + newFunction.getName() + " expected "
								+ (code.parameterCount() - 1) + " but was " + argumentCount);
					}

					// save bp/pc/code in activation zone
					//stack[activation + offset] = ??
					var activation = baseArg-1+ code.slotCount();
					stack[activation + BP_OFFSET] = bp;
					stack[activation + PC_OFFSET] = pc;
					stack[activation + FUN_OFFSET] = encodeDictObject(function,dict);

					// initialize pc, bp and sp
					pc = 0;
					bp = baseArg-1;
					sp = activation + ACTIVATION_SIZE;

					// initialize all locals that are not parameters
					for (var i = bp + code.parameterCount(); i < bp + code.slotCount(); i++) {
						stack[i] = undefined;
					}

					// initialize function and instrs of the new function
					function = newFunction;
					instrs = code.instrs();

					// DEBUG
					 dumpStack(">end funcall dump", stack, sp, bp, dict, heap);
				}
				case Instructions.RET -> {
					//throw new UnsupportedOperationException("TODO RET");
					// DEBUG
					dumpStack("> start ret dump", stack, sp, bp, dict, heap);

					// get the return value from the top of the stack
					int result =pop(stack,--sp);

					System.err.println("ret " + decodeAnyValue(result, dict, heap));

					// find activation and restore pc
					int activation = bp+ code.slotCount();
					pc = stack[activation + PC_OFFSET];
					if (pc == 0) {
					 //end of the interpreter
						return decodeAnyValue(result, dict, heap);
					}

					// restore sp, function and bp
					sp = bp-1;
					function = (JSObject) decodeDictObject(stack[activation + FUN_OFFSET],dict);
					bp = stack[activation + BP_OFFSET];

					// restore code and instrs
					code = (Code) function.lookup("__code__");
					instrs = code.instrs();

					// push return value
					push(stack,sp++,result);

					// DEBUG
					 dumpStack("> end ret dump", stack, sp, bp, dict, heap);
				}
				case Instructions.GOTO -> {
					//throw new UnsupportedOperationException("TODO GOTO");
					// get the label
					int label = instrs[pc];;
					// change the program counter to the label
					pc = label;
				}
				case Instructions.JUMP_IF_FALSE -> {
					//throw new UnsupportedOperationException("TODO JUMP_IF_FALSE");
					// get the label

					var label =instrs[pc++];
					// get the value on top of the stack
					var condition = pop(stack,--sp);
					// if condition is false change the program counter to the label
					if (condition == TagValues.FALSE) {
						pc = label;
					}
				}
				case Instructions.NEW -> {
					//throw new UnsupportedOperationException("TODO NEW");
					// get the class from the instructions
					var vClass = instrs[pc++];
					var clazz = (JSObject)  decodeAnyValue(vClass,dict,heap);;

					// out of memory ?
					if (hp + OBJECT_HEADER_SIZE + clazz.length() >= heap.length) {
						dumpHeap("before GC ", heap, hp, dict);

						throw new UnsupportedOperationException("TODO !!! GC !!!");

					//	dumpHeap("after GC ", heap, hp, dict);
					}

					var ref = hp;

					// write the class on heap
					heap[ref] = vClass;
					// write the empty GC mark
					heap[ref + GC_OFFSET] = GC_EMPTY;
					// get all fields values from the stack and write them on heap
					var baseArg = sp - clazz.length();
					for (var i = 0; i < clazz.length(); i++) {
						heap[ref + OBJECT_HEADER_SIZE + i] = stack[baseArg + i];
					}

					// adjust stack pointer and heap pointer
					sp = baseArg;
					hp += OBJECT_HEADER_SIZE+ clazz.length();

					// push the reference on top of the stack
					push(stack,sp++,encodeReference(ref));

				}
				case Instructions.GET -> {
					//throw new UnsupportedOperationException("TODO GET");
					// get field name from the instructions
					var fieldName = (String) decodeDictObject(instrs[pc++],dict);

					// get reference from the top of the stack
					int value = pop(stack,--sp) ;
					int ref = decodeReference(value);
					// get class on heap from the reference
					int vClass = heap[ref];
					// get JSObject from class
					var clazz = (JSObject) decodeDictObject(vClass, dict);
					// get field slot from JSObject
					var slot = clazz.lookup(fieldName);
					System.out.println(slot);
					if (slot == UNDEFINED) {
					// no slot, push undefined
						push(stack,sp++,undefined);
						continue;
					}

					// get the field index
					int fieldIndex = (int) slot;
					// get field value

					int fieldValue = heap[ref+OBJECT_HEADER_SIZE+fieldIndex];
					// push field value on top of the stack
					push(stack,sp++,fieldValue);
				}
				case Instructions.PUT -> {
					//throw new UnsupportedOperationException("TODO PUT");
					// get field name from the instructions
					var fieldName = (String) decodeDictObject(instrs[pc++],dict);
					// get new value from the top of the stack
					var value = pop(stack,--sp);
					// get reference from the top of the stack
					 var ref = pop(stack,--sp);
					// get class on heap from the reference
					var vClass = heap[ref];
					// get JSObject from class
					var clazz = (JSObject) decodeDictObject(vClass, dict);
					// get field slot from JSObject
					var slotOrUndefined = clazz.lookup(fieldName);
					if (slotOrUndefined == UNDEFINED) {
						throw new Failure("invalid field " + fieldName);
					}

					// get the field index
					var fieldIndex = (int) slotOrUndefined;
					// store field value from the top of the stack on heap
					heap[ref+OBJECT_HEADER_SIZE+fieldIndex] = value;
				}
				case Instructions.PRINT -> {
					//throw new UnsupportedOperationException("TODO PRINT");

					// pop the value on top of the stack
					var result = pop(stack,--sp);
					// decode the value
					var value = decodeAnyValue(result,dict,heap);
					// find "print" in the global environment
					var print = (JSObject) globalEnv.lookup("print");
					// invoke it
					print.invoke(UNDEFINED, new Object[]{ value });
					// push undefined on the stack
					push(stack,sp++,undefined);
				}
				default -> throw new AssertionError("unknown instruction " + instrs[pc - 1]);
			}
		}
	}


	@SuppressWarnings("unchecked")
	public static JSObject createGlobalEnv(PrintStream outStream) {
		JSObject globalEnv = JSObject.newEnv(null);
		globalEnv.register("global", globalEnv);
		globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
			System.err.println("print called with " + Arrays.toString(args));
			outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
			return UNDEFINED;
		}));
		globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
		globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
		globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
		globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
		globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] % (Integer) args[1]));

		globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
		globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
		globalEnv.register("<", JSObject.newFunction("<",
				(self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
		globalEnv.register("<=", JSObject.newFunction("<=",
				(self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
		globalEnv.register(">", JSObject.newFunction(">",
				(self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
		globalEnv.register(">=", JSObject.newFunction(">=",
				(self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));

		return globalEnv;
	}

	public static void interpret(Script script, PrintStream outStream) {
		JSObject globalEnv = createGlobalEnv(outStream);
		var body = script.body();
		var function = InstrRewriter.createFunction(Optional.of("main"), List.of(), body, new Dictionary(),
				globalEnv);
		function.invoke(UNDEFINED, new Object[0]);
	}
}
