package org.eln2.mc.common.network

import net.minecraft.network.FriendlyByteBuf

fun interface NetworkSerializer<T> {
    fun write(obj: T, output: FriendlyByteBuf)
}

fun interface NetworkDeserializer<T> {
    fun read(input: FriendlyByteBuf) : T
}
