package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.control.ControlClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlClientTest {
    private val status = Json.parseToJsonElement(
        """
        {"server":{
          "groups":[
            {"id":"g-kitchen","name":"","stream_id":"radio","muted":false,"clients":[
              {"id":"kitchen","config":{"name":"","latency":0,"volume":{"muted":false,"percent":80}},
               "host":{"name":"kitchen-pi"},"connected":true}]},
            {"id":"g-living","name":"Downstairs","stream_id":"spotify","muted":false,"clients":[
              {"id":"snaptv-abc","config":{"name":"Living room","latency":0,"volume":{"muted":false,"percent":100}},
               "host":{"name":"Google TV"},"connected":true}]}
          ],
          "streams":[
            {"id":"radio","status":"playing","properties":{"metadata":{"title":"News"}}},
            {"id":"spotify","status":"playing","properties":{"metadata":{"title":"Song","artist":["A","B"],"album":"LP"}}}
          ]}}
        """.trimIndent()
    )

    @Test
    fun findsOurRoomAndTrack() {
        val room = ControlClient.parseStatus(status, "snaptv-abc")!!
        assertEquals("Living room", room.clientName)
        assertEquals("Downstairs", room.groupName)
        assertEquals("g-living", room.groupId)
        assertEquals("spotify", room.stream?.id)
        assertEquals("Song", room.stream?.track?.title)
        assertEquals("A, B", room.stream?.track?.artist)
        assertEquals(listOf("radio", "spotify"), room.streams.map { it.id })
    }

    @Test
    fun fallsBackToHostName() {
        assertEquals("kitchen-pi", ControlClient.parseStatus(status, "kitchen")!!.clientName)
    }

    @Test
    fun unknownClientIsNull() {
        assertNull(ControlClient.parseStatus(status, "nobody"))
    }
}
