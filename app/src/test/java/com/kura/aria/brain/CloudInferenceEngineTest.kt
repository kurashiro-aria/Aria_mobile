package com.kura.aria.brain

import com.kura.aria.brain.cloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class CloudInferenceEngineTest {
    private class FakeClient(var health:Boolean=true, var result:CloudBrainResult=CloudBrainResult(BrainResponse("ok","r")), var failure:Throwable?=null):CloudBrainClient{
        override suspend fun health()=health
        override suspend fun respond(request:BrainRequest):CloudBrainResult{failure?.let{throw it};return result}
    }
    @Test fun connectingReadyAndGeneration(){runBlocking{val c=FakeClient();val e=CloudInferenceEngine(CloudBrainConfig("https://example.invalid"),c);assertTrue(e.connect());assertEquals(BrainState.Ready,e.state);assertEquals(listOf("ok"),e.generate(BrainRequest("p",1,"r")).toList());assertEquals(BrainState.Ready,e.state)}}
    @Test fun mismatchedIdFails(){runBlocking{val e=CloudInferenceEngine(CloudBrainConfig("https://example.invalid"),FakeClient(result=CloudBrainResult(BrainResponse("ok","wrong"))));e.connect();try{e.generate(BrainRequest("p",1,"r")).toList();fail()}catch(_:CloudBrainException.InvalidResponse){};assertTrue(e.state is BrainState.Error)}}
    @Test fun emptyFails(){runBlocking{val e=CloudInferenceEngine(CloudBrainConfig("https://example.invalid"),FakeClient(result=CloudBrainResult(BrainResponse("","r"))));e.connect();try{e.generate(BrainRequest("p",1,"r")).toList();fail()}catch(_:CloudBrainException.InvalidResponse){}}}
    @Test fun errorCanRecover(){runBlocking{val c=FakeClient();val e=CloudInferenceEngine(CloudBrainConfig("https://example.invalid"),c);e.connect();c.failure=CloudBrainException.ServerUnavailable();try{e.generate(BrainRequest("p",1,"r")).toList()}catch(_:Throwable){};assertTrue(e.state is BrainState.Error);c.failure=null;assertTrue(e.recover());assertEquals(BrainState.Ready,e.state)}}
    @Test fun cancellationReturnsReady(){runBlocking{val c=object:CloudBrainClient{override suspend fun health()=true;override suspend fun respond(request:BrainRequest):CloudBrainResult{delay(5000);return CloudBrainResult(BrainResponse("late",request.requestId))}};val e=CloudInferenceEngine(CloudBrainConfig("https://example.invalid"),c);e.connect();val job=launch{e.generate(BrainRequest("p",1,"r")).toList()};delay(20);job.cancelAndJoin();assertEquals(BrainState.Ready,e.state)}}
}
