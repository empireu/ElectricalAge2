package org.eln2.mc

annotation class ClientOnly
annotation class ServerOnly
annotation class OnServerThread
annotation class OnClientThread
annotation class OnSimulationThread
annotation class CalledOnce

/**
 * Indicates that the code element is accessed from multiple threads.
 * */
annotation class CrossThreadAccess
