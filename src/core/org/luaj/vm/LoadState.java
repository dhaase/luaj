/*******************************************************************************
* Copyright (c) 2007 LuaJ. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package org.luaj.vm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;




/*
** Function Prototypes
*/
public class LoadState {
	
	/** Interface for the compiler, if it is installed. */
	public interface LuaCompiler {
		public LPrototype compile(int firstByte, InputStream stream, String name) throws IOException;
	}

	/** Compiler instance, if installed */
	public static LuaCompiler compiler = null;

	/** Signature byte indicating the file is a compiled binary chunk */
	private static final byte[] LUA_SIGNATURE	= "\033Lua".getBytes();

	/** Name for compiled chunks */
	public static final String SOURCE_BINARY_STRING = "binary string";


	/** for header of binary files -- this is Lua 5.1 */
	public static final int LUAC_VERSION		= 0x51;

	/** for header of binary files -- this is the official format */
	public static final int LUAC_FORMAT		= 0;

	/** size of header of binary files */
	public static final int LUAC_HEADERSIZE		= 12;

	// values read from the header
	private int     luacVersion;
	private int     luacFormat;
	private boolean luacLittleEndian;
	private int     luacSizeofInt;
	private int     luacSizeofSizeT;
	private int     luacSizeofInstruction;
	private int     luacSizeofLuaNumber;
	private boolean luacIsNumberIntegral;

	/** input stream from which we are loading */
	private DataInputStream is;

	/** Name of what is being loaded? */
	String name;

	/** The VM doing the loading */
	LuaState L;
	
	int loadByte() throws IOException {
		return is.readUnsignedByte();
	}

	int loadInt() throws IOException {
		if ( this.luacLittleEndian ) {
			int a = is.readUnsignedByte();
			int b = is.readUnsignedByte();
			int c = is.readUnsignedByte();
			int d = is.readUnsignedByte();
			return (d << 24) | (c << 16) | (b << 8) | a;
		} else {
			return is.readInt();
		}
	}
	
	long loadInt64() throws IOException {
		int a,b;
		if ( this.luacLittleEndian ) {
			a = loadInt();
			b = loadInt();
		} else {
			b = loadInt();
			a = loadInt();
		}
		return (((long)b)<<32) | (((long)a)&0xffffffffL);
	}

	LString loadString() throws IOException {
		int size = loadInt();
		if ( size == 0 )
			return null;
		byte[] bytes = new byte[size];
		is.readFully( bytes );
		return new LString( bytes, 0, bytes.length - 1 );
	}
	
	public static LNumber longBitsToLuaNumber( long bits ) {
		if ( ( bits & ( ( 1L << 63 ) - 1 ) ) == 0L ) {
			return LInteger.valueOf( 0 );
		}
		
		int e = (int)((bits >> 52) & 0x7ffL) - 1023;
		
		if ( e >= 0 && e < 31 ) {
			long f = bits & 0xFFFFFFFFFFFFFL;
			int shift = 52 - e;
			long intPrecMask = ( 1L << shift ) - 1;
			if ( ( f & intPrecMask ) == 0 ) {
				int intValue = (int)( f >> shift ) | ( 1 << e );
				return LInteger.valueOf( ( ( bits >> 63 ) != 0 ) ? -intValue : intValue );
			}
		}
		
		double value = Double.longBitsToDouble(bits);
		return new LDouble( value );
	}
	
	LNumber loadNumber() throws IOException {
		if ( this.luacIsNumberIntegral ) {
			int value = loadInt();
			return LInteger.valueOf( value );
		} else {
			return longBitsToLuaNumber( loadInt64() );
		}
	}

	public void loadCode( LPrototype f ) throws IOException {
		int n = loadInt();
		int[] code = new int[n];
		for ( int i=0; i<n; i++ )
			code[i] = loadInt();
		f.code = code;
	}

	void loadConstants(LPrototype f) throws IOException {
		int n = loadInt();
		LValue[] values = new LValue[n];
		for ( int i=0; i<n; i++ ) {
			switch ( loadByte() ) {
			case Lua.LUA_TNIL:
				values[i] = LNil.NIL;
				break;
			case Lua.LUA_TBOOLEAN:
				values[i] = (0 != loadByte()? LBoolean.TRUE: LBoolean.FALSE);
				break;
			case Lua.LUA_TNUMBER:
				values[i] = loadNumber();
				break;
			case Lua.LUA_TSTRING:
				values[i] = loadString();
				break;
			default:
				throw new IllegalStateException("bad constant");
			}
		}
		f.k = values;
		
		n = loadInt();
		LPrototype[] protos = new LPrototype[n];
		for ( int i=0; i<n; i++ )
			protos[i] = loadFunction(f.source);
		f.p = protos;
	}

	void loadDebug( LPrototype f ) throws IOException {
		int n = loadInt();
		f.lineinfo = new int[n];
		for ( int i=0; i<n; i++ )
			f.lineinfo[i] = loadInt();
		
		n = loadInt();
		f.locvars = new LocVars[n];
		for ( int i=0; i<n; i++ ) {
			LString varname = loadString();
			int startpc = loadInt();
			int endpc = loadInt();
			f.locvars[i] = new LocVars(varname, startpc, endpc);
		}
		
		n = loadInt();
		f.upvalues = new LString[n];
		for ( int i=0; i<n; i++ ) {
			f.upvalues[i] = loadString();
		}
	}

	public LPrototype loadFunction(LString p) throws IOException {
		LPrototype f = new LPrototype();
//		this.L.push(f);
		f.source = loadString();
		if ( f.source == null )
			f.source = p;
		f.linedefined = loadInt();
		f.lastlinedefined = loadInt();
		f.nups = loadByte();
		f.numparams = loadByte();
		f.is_vararg = (0 != loadByte());
		f.maxstacksize = loadByte();
		loadCode(f);
		loadConstants(f);
		loadDebug(f);
		
		// TODO: add check here, for debugging purposes, I believe
		// see ldebug.c
//		 IF (!luaG_checkcode(f), "bad code");
		
//		 this.L.pop();
		 return f;
	}

	public void loadHeader() throws IOException {
		luacVersion = is.readByte();
		luacFormat = is.readByte();
		luacLittleEndian = (0 != is.readByte());
		luacSizeofInt = is.readByte();
		luacSizeofSizeT = is.readByte();
		luacSizeofInstruction = is.readByte();
		luacSizeofLuaNumber = is.readByte();
		luacIsNumberIntegral = (0 != is.readByte());
	}
	
	public static LPrototype undump( LuaState L, InputStream stream, String name ) throws IOException {
		
		// check first byte to see if its a precompiled chunk 
		int c = stream.read();
		if ( c != LUA_SIGNATURE[0] ) {
			if ( compiler != null )
				return compiler.compile(c, stream, name);
			throw new IllegalArgumentException("no compiler");
		}

		// check rest of signature
		for ( int i=1; i<4; i++ ) {
			if ( stream.read() != LUA_SIGNATURE[i] )
				throw new IllegalArgumentException("bad signature");
		}
		
		// load file as a compiled chunk
		String sname = getSourceName(name);
		LoadState s = new LoadState( L, stream, sname );
		s.loadHeader();
		LString literal = new LString("=?");
		return s.loadFunction( literal );
	}

    public static String getSourceName(String name) {
        String sname = name;
        if ( name.startsWith("@") || name.startsWith("=") )
			sname = name.substring(1);
		else if ( name.startsWith("\033") )
			sname = SOURCE_BINARY_STRING;
        return sname;
    }

	/** Private constructor for create a load state */
	private LoadState( LuaState L, InputStream stream, String name ) {
		this.L = L;
		this.name = name;
		this.is = new DataInputStream( stream );
	}
}