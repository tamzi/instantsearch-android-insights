package com.algolia.instantsearch

import com.algolia.instantsearch.insights.Insights
import com.algolia.instantsearch.insights.converter.ConverterEventToString
import com.algolia.instantsearch.insights.converter.ConverterParameterToString
import com.algolia.instantsearch.insights.converter.ConverterStringToEvent
import com.algolia.instantsearch.insights.event.Event
import com.algolia.instantsearch.insights.event.EventUploader
import com.algolia.instantsearch.insights.webservice.WebService
import com.algolia.instantsearch.insights.webservice.uploadEvents
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue


@RunWith(JUnit4::class)
class InsightsTest {
    private val responseOK = WebService.Response(null, 200)
    private val responseNotFound = WebService.Response("", 404)
    private var firstEvent = Event.Click(TestUtils.eventParametersClick, TestUtils.indexName)
    private var secondEvent = Event.Conversion(TestUtils.eventParametersConversion, TestUtils.indexName)
    private var thirdEvent = Event.View(TestUtils.eventParametersView, TestUtils.indexName)

    @Test
    fun testEventConverters() {
        val string = ConverterEventToString.convert(firstEvent)
        val event = ConverterStringToEvent.convert(string)
        assertEquals(firstEvent, event)
    }

    @Test
    fun testParameterConverter() {
        val string = ConverterParameterToString.convert(firstEvent.params)
        firstEvent.params.entries.forEach {
            assertTrue(string.contains(Regex("\"${it.key}\":\"?${it.value}\"?")),
                "The string should contain the firstEvent's ${it.key}: $string.")
        }
    }

    @Test
    fun testClickEvent() {
        assertEquals(responseOK, TestUtils.webService.send(firstEvent))
    }

    @Test
    fun testViewEvent() {
        assertEquals(responseNotFound, TestUtils.webService.send(thirdEvent))
    }

    @Test
    fun testConversionEvent() {
        assertEquals(responseOK, TestUtils.webService.send(secondEvent))
    }

    /**
     * Tests the integration of events, WebService and Database.
     */
    @Test
    fun testIntegration() {
        val events = mutableListOf(firstEvent, secondEvent, thirdEvent)
        val database = MockDatabase(TestUtils.indexName, events)
        val webService = MockWebService()
        val uploader = AssertingEventUploader(events, webService, database)
        val insights = Insights(TestUtils.indexName, uploader, database, webService)

        webService.code = 200 // Given a working web service
        insights.click(firstEvent.params)
        webService.code = -1 // Given a web service that errors
        insights.conversion(secondEvent.params)
        webService.code = 400 // Given a working web service returning an HTTP error
        insights.view(thirdEvent.params)

        webService.code = -1 // Given a web service that errors
        insights.search.click(firstEvent.params)
        insights.personalization.click(firstEvent.params)
        insights.personalization.conversion(secondEvent.params)
        webService.code = 200 // Given a working web service
        insights.personalization.view(thirdEvent.params)
    }

    inner class AssertingEventUploader internal constructor(
        private val events: MutableList<Event>,
        private val webService: MockWebService,
        private val database: MockDatabase
    ) : EventUploader {

        private var count: Int = 0

        override fun startPeriodicUpload() {
            assertEquals(events, database.read())
            webService.uploadEvents(database, TestUtils.indexName)
            assert(database.read().isEmpty())
        }

        override fun startOneTimeUpload() {
            when (count) {
                0 -> assertEquals(listOf(firstEvent), database.read()) // expect added first
                1 -> assertEquals(listOf(secondEvent), database.read()) // expect flush then added second
                2 -> assertEquals(listOf(secondEvent, thirdEvent), database.read()) // expect added third

                3 -> assertEquals(listOf(firstEvent), database.read()) // expect flush then added first
                4 -> assertEquals(listOf(firstEvent, firstEvent), database.read()) // expect added first
                5 -> assertEquals(listOf(firstEvent, firstEvent, secondEvent), database.read()) // expect added second
                6 -> assertEquals(listOf(firstEvent, firstEvent, secondEvent, thirdEvent), database.read()) // expect added third

            }
            webService.uploadEvents(database, TestUtils.indexName)
            when (count) {
                0 -> assert(database.read().isEmpty()) // expect flushed first
                1 -> assertEquals(listOf(secondEvent), database.read()) // expect kept second
                2 -> assert(database.read().isEmpty()) // expect flushed events

                3 -> assertEquals(listOf(firstEvent), database.read()) // expect kept first
                4 -> assertEquals(listOf(firstEvent, firstEvent), database.read()) // expect kept first2
                5 -> assertEquals(listOf(firstEvent, firstEvent, secondEvent), database.read()) // expect kept second
                6 -> assert(database.read().isEmpty()) // expect flushed events
            }
            count++
        }
    }
}
