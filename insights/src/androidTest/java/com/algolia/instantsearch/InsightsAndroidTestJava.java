package com.algolia.instantsearch;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.algolia.instantsearch.insights.Insights;
import com.algolia.instantsearch.insights.InstantSearchInsightsException;
import com.algolia.instantsearch.insights.event.Event;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;


@RunWith(AndroidJUnit4.class)
public class InsightsAndroidTestJava {

    private Context context = InstrumentationRegistry.getContext();
    private Insights.Configuration configuration = new Insights.Configuration(5000, 5000);

    @Test
    public void testInitShouldFail() {
        try {
            Insights.shared("index");
        } catch (Exception exception) {
            assertEquals(exception.getClass(), InstantSearchInsightsException.IndexNotRegistered.class);
        }
    }

    @Test
    public void testInitShouldWork() {
        Insights insights = Insights.register(context, "testApp", "testKey", "index", configuration);
        Insights insightsShared = Insights.shared("index");
        Map<String, ?> map = Collections.emptyMap();

        assertEquals(insights, insightsShared);
        insightsShared.track(new Event.Click(map));
    }
}
