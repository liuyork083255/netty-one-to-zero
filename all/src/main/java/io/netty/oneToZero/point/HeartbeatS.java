package io.netty.oneToZero.point;

/**
 * 心跳
 *  心跳的意义在于及时发现对方处于 idle 状态，也就是离线状态，做出及时的相关措施
 *
 *  TCP 协议层面其实也有心跳机制：keep-alive，但是默认是关闭的，并且时间是 2 小时，如果采用的不是 TCP 协议而是类似 UDP，那么 keep-live 就失效了
 *  所以心跳机制基本都是采用应用层自定义方式
 *
 *
 */
public class HeartbeatS {
}