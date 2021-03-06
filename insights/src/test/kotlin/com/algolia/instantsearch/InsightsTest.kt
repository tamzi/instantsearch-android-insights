package com.algolia.instantsearch

import com.algolia.instantsearch.insights.BuildConfig
import com.algolia.instantsearch.insights.Insights
import com.algolia.instantsearch.insights.converter.ConverterEventInternalToString
import com.algolia.instantsearch.insights.converter.ConverterEventToEventInternal
import com.algolia.instantsearch.insights.converter.ConverterStringToEventInternal
import com.algolia.instantsearch.insights.event.Event
import com.algolia.instantsearch.insights.event.EventInternal
import com.algolia.instantsearch.insights.event.EventObjects
import com.algolia.instantsearch.insights.event.EventUploader
import com.algolia.instantsearch.insights.webservice.WebService
import com.algolia.instantsearch.insights.webservice.WebServiceHttp
import com.algolia.instantsearch.insights.webservice.uploadEvents
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


@RunWith(JUnit4::class)
internal class InsightsTest {

    private val responseOK = WebService.Response(null, 200)
    private val eventA = "EventA"
    private val eventB = "EventB"
    private val eventC = "EventC"
    private val indexName = "latency"
    private val appId = BuildConfig.ALGOLIA_APPLICATION_ID
    private val apiKey = BuildConfig.ALGOLIA_API_KEY
    private val queryId = "6de2f7eaa537fa93d8f8f05b927953b1"
    private val userToken = "foobarbaz"
    private val positions = listOf(1)
    private val objectIDs = EventObjects.IDs("54675051")
    private val filters = EventObjects.Filters("foo:bar")
    private val timestamp = System.currentTimeMillis()
    private val configuration = Insights.Configuration(
        connectTimeoutInMilliseconds = 5000,
        readTimeoutInMilliseconds = 5000
    )
    private val eventClick = Event.Click(
        eventA,
        userToken,
        timestamp,
        objectIDs,
        queryId,
        positions
    )
    private val eventConversion = Event.Conversion(
        eventB,
        userToken,
        timestamp,
        objectIDs,
        queryId
    )
    private val eventView = Event.View(
        eventC,
        userToken,
        timestamp,
        filters,
        queryId
    )
    private val click = ConverterEventToEventInternal.convert(eventClick to indexName)
    private val conversion = ConverterEventToEventInternal.convert(eventConversion to indexName)
    private val view = ConverterEventToEventInternal.convert(eventView to indexName)
    private val webService
        get() = WebServiceHttp(
            appId = appId,
            apiKey = apiKey,
            environment = WebServiceHttp.Environment.Prod,
            connectTimeoutInMilliseconds = configuration.connectTimeoutInMilliseconds,
            readTimeoutInMilliseconds = configuration.readTimeoutInMilliseconds
        )

    @Test
    fun testEventConverters() {
        val internal = ConverterEventToEventInternal.convert(eventClick to indexName)
        val string = ConverterEventInternalToString.convert(internal)
        val event = ConverterStringToEventInternal.convert(string)
        assertEquals(internal, event)
    }

    @Test
    fun testClickEvent() {
        assertEquals(responseOK, webService.send(click))
    }

    @Test
    fun testViewEvent() {
        assertEquals(responseOK, webService.send(view))
    }

    @Test
    fun testConversionEvent() {
        assertEquals(responseOK, webService.send(conversion))
    }

    @Test
    fun testMethods() {
        val events = mutableListOf(click, conversion, view)
        val database = MockDatabase(indexName, events)
        val webService = MockWebService()
        val uploader = object : AssertingEventUploader(events, webService, database) {
            override fun startOneTimeUpload() {
                val trackedEvents = database.read()
                assertEquals(5, trackedEvents.size, "Five events should have been tracked")
                assertTrue(trackedEvents.contains(click), "The clicked event should have been tracked through clicked and clickedAfterSearch")
                assertTrue(trackedEvents.contains(click), "The converted event should have been tracked through converted and convertedAfterSearch")
                assertTrue(trackedEvents.contains(click), "The viewed event should have been tracked through viewed")
            }
        }
        val insights = Insights(indexName, uploader, database, webService)
        insights.userToken = "foo" //TODO: git stash apply to use default UUID token

        insights.clicked(eventClick.eventName, eventClick.eventObjects as EventObjects.IDs)
        insights.clickedAfterSearch(eventClick.eventName, eventClick.queryId!!, eventClick.eventObjects as EventObjects.IDs, eventClick.positions!!)
        insights.converted(eventConversion.eventName, eventClick.eventObjects as EventObjects.IDs)
        insights.convertedAfterSearch(eventConversion.eventName, eventConversion.queryId!!, eventClick.eventObjects as EventObjects.IDs)
        insights.viewed(eventView.eventName, eventView.eventObjects as EventObjects.Filters)
    }

    @Test
    fun testEnabled() {
        val events = mutableListOf(click, conversion, view)
        val database = MockDatabase(indexName, events)
        val webService = MockWebService()
        val uploader = object : AssertingEventUploader(events, webService, database) {
            override fun startOneTimeUpload() {
                val trackedEvents = database.read()
                assertFalse(trackedEvents.contains(click), "The first event should have been ignored")
                assertTrue(trackedEvents.contains(conversion), "The second event should be uploaded")
            }
        }
        val insights = Insights(indexName, uploader, database, webService)
        insights.minBatchSize = 1 // Given an Insights that uploads every event

        insights.enabled = false // When a firstEvent is sent with insight disabled
        insights.clicked(eventClick)
        insights.enabled = true // And a secondEvent sent with insight enabled
        insights.converted(eventConversion)
    }

    @Test
    fun testMinBatchSize() {
        val events = mutableListOf(click, conversion, view)
        val database = MockDatabase(indexName, events)
        val webService = MockWebService()
        val uploader = MinBatchSizeEventUploader(events, webService, database)
        val insights = Insights(indexName, uploader, database, webService)

        // Given a minBatchSize of one and one event
        insights.minBatchSize = 1
        insights.track(eventClick)
        // Given a minBatchSize of two and two events
        insights.minBatchSize = 2
        insights.track(eventClick)
        insights.track(eventClick)
        // Given a minBatchSize of four and four events
        insights.minBatchSize = 4
        insights.track(eventClick)
        insights.track(eventClick)
        insights.track(eventClick)
        insights.track(eventClick)
    }

    inner class MinBatchSizeEventUploader(
        events: MutableList<EventInternal>,
        webService: MockWebService,
        private val database: MockDatabase
    ) : AssertingEventUploader(events, webService, database) {

        override fun startOneTimeUpload() {
            when (count) {
                // Expect a single event on first call
                0 -> assertEquals(1, database.count(), "startOneTimeUpload should be called first with one event")
                // Expect two events on second call
                1 -> assertEquals(2, database.count(), "startOneTimeUpload should be called second with two events")
                // Expect two events on third call
                2 -> assertEquals(4, database.count(), "startOneTimeUpload should be called third with four events")
            }
            count++
            database.clear()
        }
    }

    /**
     * Tests the integration of events, WebService and Database.
     */
    @Test
    fun testIntegration() {
        val events = mutableListOf(click, conversion, view)
        val database = MockDatabase(indexName, events)
        val webService = MockWebService()
        val uploader = IntegrationEventUploader(events, webService, database)
        val insights = Insights(indexName, uploader, database, webService)
        insights.minBatchSize = 1

        webService.code = 200 // Given a working web service
        insights.track(eventClick)
        webService.code = -1 // Given a web service that errors
        insights.track(eventConversion)
        webService.code = 400 // Given a working web service returning an HTTP error
        insights.track(eventView) // When tracking an event

        webService.code = -1 // Given a web service that errors
        insights.userToken = userToken // Given an userToken

        // When adding events without explicitly-provided userToken
        insights.clickedAfterSearch(
            eventName = eventA,
            queryId = queryId,
            objectIDs = objectIDs,
            positions = positions,
            timestamp = timestamp
        )
        insights.clicked(
            eventName = eventA,
            timestamp = timestamp,
            objectIDs = objectIDs
        )
        insights.convertedAfterSearch(
            eventName = eventB,
            timestamp = timestamp,
            queryId = queryId,
            objectIDs = objectIDs
        )
        webService.code = 200 // Given a working web service
        insights.viewed(eventView)
    }

    inner class IntegrationEventUploader(
        events: MutableList<EventInternal>,
        private val webService: MockWebService,
        private val database: MockDatabase
    ) : AssertingEventUploader(events, webService, database) {

        override fun startOneTimeUpload() {
            val clickEventNotForSearch = Event.Click(
                eventName = eventA,
                userToken = userToken,
                timestamp = timestamp,
                eventObjects = objectIDs,
                positions = null // A Click event not for Search has no positions
            )
            val clickEventInternal = ConverterEventToEventInternal.convert(clickEventNotForSearch to indexName)

            when (count) {
                0 -> assertEquals(listOf(click), database.read(), "failed 0") // expect added first
                1 -> assertEquals(listOf(conversion), database.read(), "failed 1") // expect flush then added second
                2 -> assertEquals(listOf(conversion, view), database.read(), "failed 2")

                3 -> assertEquals(listOf(click), database.read(), "failed 3") // expect flush then added first
                4 -> assertEquals(listOf(click, clickEventInternal), database.read(), "failed 4") // expect added first
                5 -> assertEquals(listOf(click, clickEventInternal, conversion), database.read(), "failed 5") // expect added second
                6 -> assertEquals(listOf(click, clickEventInternal, conversion, view), database.read(), "failed 6") // expect added third

            }
            webService.uploadEvents(database, indexName)
            when (count) {
                0 -> assert(database.read().isEmpty()) // expect flushed first
                1 -> assertEquals(listOf(conversion), database.read()) // expect kept second
                2 -> assert(database.read().isEmpty()) // expect flushed events

                3 -> assertEquals(listOf(click), database.read()) // expect kept first
                4 -> assertEquals(listOf(click, clickEventInternal), database.read()) // expect kept first2
                5 -> assertEquals(listOf(click, clickEventInternal, conversion), database.read()) // expect kept second
                6 -> assert(database.read().isEmpty()) // expect flushed events
            }
            count++
        }
    }

    abstract inner class AssertingEventUploader(
        private val events: MutableList<EventInternal>,
        private val webService: MockWebService,
        private val database: MockDatabase
    ) : EventUploader {

        protected var count: Int = 0

        override fun startPeriodicUpload() {
            assertEquals(events, database.read())
            webService.uploadEvents(database, indexName)
            assert(database.read().isEmpty())
        }
    }
}
