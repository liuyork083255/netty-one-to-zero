package io.netty.oneToZero;

public class BitCalculateS {

    /**
     *
     * &    =>  1&1=1 , 1&0=0 , 0&1=0 , 0&0=0
     * |    =>  1|0 = 1 , 1|1 = 1 , 0|0 = 0 , 0|1 = 1
     * ^    =>  1^0 = 1 , 1^1 = 0 , 0^1 = 1 , 0^0 = 0
     * ~    =>  0000 0101，取反后为1111 1010
     * >>>  =>  无符号右移运算符和右移运算符的主要区别在于负数的计算，因为无符号右移是高位补0，移多少位补多少个0
     * <<   =>  正数左边第一位补0，负数补1
     * >>   =>  正数左边第一位补0，负数补1
     *
     */
}