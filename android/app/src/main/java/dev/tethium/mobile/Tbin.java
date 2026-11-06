package dev.tethium.mobile;

import org.json.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Phone side of the link encoding; mirrors dev.tethium.link.Tbin on the laptop byte for byte. */
final class Tbin {
    private Tbin(){}
    static final class Writer {
        private final Map<String,Integer> keys=new HashMap<>();
        byte[] encode(int kind,Object body){var out=new ByteArrayOutputStream(256);out.write(kind);write(out,body);return out.toByteArray();}
        private void write(ByteArrayOutputStream out,Object v){
            if(v==null||v==JSONObject.NULL)out.write(0);
            else if(v instanceof Boolean b)out.write(b?2:1);
            else if(v instanceof Integer||v instanceof Long||v instanceof Short||v instanceof Byte||v instanceof BigInteger){out.write(3);varint(out,zigzag(((Number)v).longValue()));}
            else if(v instanceof Number n){out.write(4);long bits=Double.doubleToLongBits(n.doubleValue());for(int i=7;i>=0;i--)out.write((int)(bits>>>(8*i)));}
            else if(v instanceof JSONArray a){out.write(6);varint(out,a.length());for(int i=0;i<a.length();i++)write(out,a.opt(i));}
            else if(v instanceof JSONObject o){out.write(7);varint(out,o.length());var names=o.keys();while(names.hasNext()){String k=names.next();key(out,k);write(out,o.opt(k));}}
            else{out.write(5);text(out,v.toString());}
        }
        private void key(ByteArrayOutputStream out,String name){Integer id=keys.get(name);if(id==null){out.write(0);keys.put(name,keys.size()+1);text(out,name);}else varint(out,id);}
    }
    static final class Reader {
        private final List<String> keys=new ArrayList<>();
        private byte[] b;private int p;
        Object decode(byte[] data,int offset)throws JSONException{b=data;p=offset;Object v=read();b=null;return v;}
        private Object read()throws JSONException{
            int tag=next();
            switch(tag){
                case 0:return JSONObject.NULL;
                case 1:return Boolean.FALSE;
                case 2:return Boolean.TRUE;
                case 3:{long v=unzigzag(varint());return v==(int)v?(Object)(int)v:(Object)v;}
                case 4:{long bits=0;for(int i=0;i<8;i++)bits=(bits<<8)|next();return Double.longBitsToDouble(bits);}
                case 5:return text();
                case 6:{int n=count();var a=new JSONArray();for(int i=0;i<n;i++)a.put(read());return a;}
                case 7:{int n=count();var o=new JSONObject();for(int i=0;i<n;i++){long id=varint();String k;if(id==0){k=text();keys.add(k);}else if(id<=keys.size())k=keys.get((int)id-1);else throw new JSONException("Unknown key id");o.put(k,read());}return o;}
                default:throw new JSONException("Bad tag "+tag);
            }
        }
        private int count()throws JSONException{long n=varint();if(n<0||n>b.length)throw new JSONException("Bad count");return (int)n;}
        private String text()throws JSONException{int n=count();if(p+n>b.length)throw new JSONException("Truncated");var s=new String(b,p,n,StandardCharsets.UTF_8);p+=n;return s;}
        private int next()throws JSONException{if(p>=b.length)throw new JSONException("Truncated");return b[p++]&255;}
        private long varint()throws JSONException{long v=0;int shift=0;while(true){int x=next();v|=(long)(x&127)<<shift;if((x&128)==0)return v;shift+=7;if(shift>63)throw new JSONException("Bad varint");}}
    }
    static void varint(ByteArrayOutputStream out,long v){while((v&~127L)!=0){out.write((int)((v&127)|128));v>>>=7;}out.write((int)v);}
    static void text(ByteArrayOutputStream out,String s){byte[] bytes=s.getBytes(StandardCharsets.UTF_8);varint(out,bytes.length);out.write(bytes,0,bytes.length);}
    static long zigzag(long v){return (v<<1)^(v>>63);}
    static long unzigzag(long v){return (v>>>1)^-(v&1);}
}
