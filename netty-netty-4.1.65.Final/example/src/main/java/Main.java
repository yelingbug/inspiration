/**
 * Copyright(c)) 2014-2019 Wegooooo Ltd. All rights reserved.
 * <p>
 * You may not use this file except authorized by Wegooooo.
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed is prohibited.
 */

import io.netty.buffer.AbstractByteBufAllocator;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;



/**
 * @author Yelin.G on 2021.10.10
 */
public class Main {

    static Unsafe unsafe;


    static {
        try {
            // 使用反射获取Unsafe的成员变量theUnsafe
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            //设置为可读取
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);

        } catch (NoSuchFieldException e) {
            System.out.println(e.getCause());
            throw new Error(e);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private int age;
    private long name;
    private boolean sex;
    private String str;

    public static long getFieldOffset(String fieldName) throws Exception {
        Field field = Main.class.getDeclaredField(fieldName);
        return unsafe.objectFieldOffset(field);

    }

    public static class VThrowable extends Throwable {
        private String value;

        public VThrowable(String value) {
            this.value = value;
        }

        public String getValue() {
            StackTraceElement[] sss = Optional.ofNullable(getStackTrace()).orElse(new StackTraceElement[0]);
            return value + "####" + Arrays.stream(sss).map(fs -> fs.getLineNumber() + ":" + fs.getClassName() + ":" + fs.getMethodName()).collect(Collectors.joining("\n"));
        }
    }

    public static void main(String[] args) throws Exception {
        /*long ageOffset = getFieldOffset("age");
        long nameOffset = getFieldOffset("name");
        long sexOffset = getFieldOffset("sex");
        long strOffset = getFieldOffset("str");

        System.out.println("name --> " + nameOffset);
        System.out.println("age --> " + ageOffset);

        System.out.println("sex --> " + sexOffset);
        System.out.println("str --> " + strOffset);*/


        /*List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        System.out.println(sizeTable.size());
        System.out.println(sizeTable);

        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        System.out.println(sizeTable.size());
        System.out.println(sizeTable);*/

        /*int f = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(8192);
        System.out.println(Integer.numberOfLeadingZeros(8192));
        System.out.println(Integer.toBinaryString(8192));
        System.out.println(Integer.toBinaryString(8192).length());
        int g = (int) (((long) Integer.MAX_VALUE + 1) / 2);
        System.out.println(f);
        System.out.println(g / 2);
        System.out.println(log2(chunkSize()));
        System.out.println(19 << 2);*/
        /*System.out.println(log2(1));
        System.out.println(log2(2));
        System.out.println(log2(3));*/
        /*System.out.println(1 << 21 - 4);
        System.out.println(255 + 1 << 4);

        int idx = 0;
        int size = 0;

        List<Integer> f = new LinkedList<>();

        for(int j = 0; j<4;j++) {
            f.add(4);
        }

        for(int j = 4; j<=21;j++) {
            for(int k =0; k<4;k++) {
                f.add(j);
            }
        }*/
        //System.out.println(f);
        /**
         * #392行代码给出的结果是lookupMaxSize是4096。
         */
        /*for (int i = 0; size <= 4096; i++) {

            int log2Delta = f.get(i);
            int times = 1 << log2Delta - 4;

            System.out.printf("times = %d/log2Delta=%d---------------%n", times, log2Delta);
            while (size <= 4096 && times-- > 0) {
                //size2idxTab[idx++] = i;
                idx++;
                size = idx + 1 << 4;
                System.out.printf("%d, %d, %d%n", idx, i, size);
            }
        }*/
        /*int sizeIdx = 5;
        int group = sizeIdx >> 2;
        int mod = sizeIdx & (1 << 2) - 1;
        int groupSize = group == 0? 0 :
                1 << 4 + 2 - 1 << group;
        int shift = group == 0? 1 : group;
        int lgDelta = shift + 4 - 1;
        int modSize = mod + 1 << lgDelta;
        System.out.println(group);
        System.out.println(mod);
        System.out.println(groupSize);
        System.out.println(shift);
        System.out.println(lgDelta);
        System.out.println(modSize);

        long fff = (long)2048 << 34;
        System.out.println(Integer.toBinaryString(2048));
        System.out.println(Long.toBinaryString(fff));
        System.out.println(fff);*/
        /*Object sync = new Object();
        Lock lock = new ReentrantLock();
        List<String> lll = new LinkedList<>();
        new Thread(new Runnable() {
            @Override
            public void run() {
                lock.lock();
                    while(true) {
                        try {
                            sync.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                        IntStream.range(1, 10).forEach(f -> System.out.println("consumer-" + f));
                    }
                lock.unlock();
            }
        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                    lock.lock();
                        IntStream.range(1, 10).forEach(f -> System.out.println("producer-" + f));
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        sync.notify();
                        lock.unlock();

            }
        }).start();*/
        System.out.println(log2(16*1024*1024));
        System.out.println(log2(16*1024*1024) + 1 - 4);

        System.out.println(Integer.SIZE - 1 - Integer.numberOfLeadingZeros(8192));
        System.out.println(log2((3233 << 1) - 1));
        System.out.println(guizhen(2011));

        System.out.println(pages2pageIdxCompute(7, false));
        long f = 4L;
        f |= 1L << 2;
        System.out.println(f);
        /*long f = (long) 2048 << 34;
        System.out.println(Long.toBinaryString(f));*/
        //AbstractByteBufAllocator.DEFAULT.
        /*Thread thread=new Thread(()->{

            while (true){

                //让thread线程不断运行
            }
        });
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(1000);//让主线程睡一会
        thread.interrupt();//打断正在正常运行的thread
        System.out.println(thread.isInterrupted());*/
    }

    static int guizhen(int size) {
        int x = log2((size << 1) - 1); //这个算法有点意思，意思就是按照2^n来规整，举个例子，如果size是2011，x=11，2^11=2048
        //如果是2049~4096之间的任何值，那么x=12，2^12=4096
        int shift = x < 2 + 4 + 1
                ? 0 : x - (2 + 4);

        int group = shift << 2;

        int log2Delta = x < 2 + 4 + 1
                ? 4 : x - 2 - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (size - 1 & deltaInverseMask) >> log2Delta &
                (1 << 2) - 1;
        return group + mod;
    }

    static int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << 13;
        if (pageSize > 16777216) {
            return 40;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < 2 + 13
                ? 0 : x - (2 + 13);

        int group = shift << 2;

        int log2Delta = x < 2 + 13 + 1?
                13 : x - 2 - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                (1 << 2) - 1;

        int pageIdx = group + mod;

        /*if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }*/

        return pageIdx;
    }

    static int log2(int val) {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(val);
    }

    public static int chunkSize() {
        int chunkSize = 8192;
        for (int i = 11; i > 0; i --) {
            if (chunkSize > 536870912) {
                throw new IllegalArgumentException(String.format(
                        "pageSize (%d) << maxOrder (%d) must not exceed %d", 8192, 11, 536870912 * 2));
            }
            chunkSize <<= 1;
        }
        return chunkSize;
    }

    public static int nextPower(int index) {
        int newCapacity = index;
        newCapacity |= newCapacity >>>  1;
        newCapacity |= newCapacity >>>  2;
        newCapacity |= newCapacity >>>  4;
        newCapacity |= newCapacity >>>  8;
        newCapacity |= newCapacity >>> 16;
        System.out.println(Integer.toBinaryString(newCapacity));
        newCapacity ++;
        return newCapacity;
    }

    public static String readableSize(long size) {
        if (size <= 0) {
            return "0";
        }

        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        System.out.println(digitGroups);
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + "/" + units[digitGroups];
    }
}
