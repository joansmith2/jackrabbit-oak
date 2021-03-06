/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.oak.osgi;

import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

import java.util.Dictionary;

import static org.apache.jackrabbit.oak.osgi.OsgiUtil.fallbackLookup;
import static org.apache.jackrabbit.oak.osgi.OsgiUtil.lookup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class OsgiUtilTest {

    @Test(expected = NullPointerException.class)
    public void testComponentLookupWithNullContext() {
        lookup((ComponentContext) null, "name");
    }

    @Test(expected = NullPointerException.class)
    public void testComponentLookupWithNullName() {
        lookup(mock(ComponentContext.class), null);
    }

    @Test
    public void testComponentLookupWithNotFoundValue() {
        Dictionary properties = mock(Dictionary.class);
        doReturn(null).when(properties).get("name");

        ComponentContext context = mock(ComponentContext.class);
        doReturn(properties).when(context).getProperties();

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testComponentLookupWithEmptyString() {
        Dictionary properties = mock(Dictionary.class);
        doReturn("").when(properties).get("name");

        ComponentContext context = mock(ComponentContext.class);
        doReturn(properties).when(context).getProperties();

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testComponentLookupWithNonTrimmedString() {
        Dictionary properties = mock(Dictionary.class);
        doReturn("   ").when(properties).get("name");

        ComponentContext context = mock(ComponentContext.class);
        doReturn(properties).when(context).getProperties();

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testComponentLookupWithNonStringValue() {
        Dictionary properties = mock(Dictionary.class);
        doReturn(42).when(properties).get("name");

        ComponentContext context = mock(ComponentContext.class);
        doReturn(properties).when(context).getProperties();

        assertEquals("42", lookup(context, "name"));
    }

    @Test
    public void testComponentLookupWithStringValue() {
        Dictionary properties = mock(Dictionary.class);
        doReturn("  value   ").when(properties).get("name");

        ComponentContext context = mock(ComponentContext.class);
        doReturn(properties).when(context).getProperties();

        assertEquals("value", lookup(context, "name"));
    }

    @Test(expected = NullPointerException.class)
    public void testFrameworkLookupWithNullContext() {
        lookup((BundleContext) null, "name");
    }

    @Test(expected = NullPointerException.class)
    public void testFrameworkLookupWithNullName() {
        lookup(mock(BundleContext.class), null);
    }

    @Test
    public void testFrameworkLookupWithNotFoundValue() {
        BundleContext context = mock(BundleContext.class);
        doReturn(null).when(context).getProperty("name");

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testFrameworkLookupWithEmptyString() {
        BundleContext context = mock(BundleContext.class);
        doReturn("").when(context).getProperty("name");

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testFrameworkLookupWithNonTrimmedString() {
        BundleContext context = mock(BundleContext.class);
        doReturn("   ").when(context).getProperty("name");

        assertNull(lookup(context, "name"));
    }

    @Test
    public void testFrameworkLookupWith() {
        BundleContext context = mock(BundleContext.class);
        doReturn("  value   ").when(context).getProperty("name");

        assertEquals("value", lookup(context, "name"));
    }

    @Test
    public void testFallbackLookupWithNotFoundValue() {
        Dictionary dictionary = mock(Dictionary.class);
        doReturn(null).when(dictionary).get("name");

        BundleContext bundleContext = mock(BundleContext.class);
        doReturn(null).when(bundleContext).getProperty("name");

        ComponentContext componentContext = mock(ComponentContext.class);
        doReturn(dictionary).when(componentContext).getProperties();
        doReturn(bundleContext).when(componentContext).getBundleContext();

        assertNull(fallbackLookup(componentContext, "name"));
    }

    @Test
    public void testFallbackLookupWithValueInComponent() {
        Dictionary dictionary = mock(Dictionary.class);
        doReturn("value").when(dictionary).get("name");

        BundleContext bundleContext = mock(BundleContext.class);
        doReturn(null).when(bundleContext).getProperty("name");

        ComponentContext componentContext = mock(ComponentContext.class);
        doReturn(dictionary).when(componentContext).getProperties();
        doReturn(bundleContext).when(componentContext).getBundleContext();

        assertEquals("value", fallbackLookup(componentContext, "name"));
    }

    @Test
    public void testFallbackLookupWithValueInFramework() {
        Dictionary dictionary = mock(Dictionary.class);
        doReturn(null).when(dictionary).get("name");

        BundleContext bundleContext = mock(BundleContext.class);
        doReturn("value").when(bundleContext).getProperty("name");

        ComponentContext componentContext = mock(ComponentContext.class);
        doReturn(dictionary).when(componentContext).getProperties();
        doReturn(bundleContext).when(componentContext).getBundleContext();

        assertEquals("value", fallbackLookup(componentContext, "name"));
    }

    @Test
    public void testFallbackLookupWithValueInComponentAndFramework() {
        Dictionary dictionary = mock(Dictionary.class);
        doReturn("value").when(dictionary).get("name");

        BundleContext bundleContext = mock(BundleContext.class);
        doReturn("ignored").when(bundleContext).getProperty("name");

        ComponentContext componentContext = mock(ComponentContext.class);
        doReturn(dictionary).when(componentContext).getProperties();
        doReturn(bundleContext).when(componentContext).getBundleContext();

        assertEquals("value", fallbackLookup(componentContext, "name"));
    }

}
