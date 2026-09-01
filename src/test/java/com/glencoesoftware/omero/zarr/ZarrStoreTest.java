package com.glencoesoftware.omero.zarr;

import org.junit.Assert;
import org.junit.Test;

/** Unit test for ZarrStore. */
public class ZarrStoreTest {

    @Test
    public void testSplitOnQuery() {
        String[] pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?test=zarr.zarr");
        Assert.assertEquals("/my/test/path", pathAndQuery[0]);
        Assert.assertEquals("?test=zarr.zarr", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?query=test1?query=test2");
        Assert.assertEquals("/my/test/path?query=test1", pathAndQuery[0]);
        Assert.assertEquals("?query=test2", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?/more/path/stuff/query=test1");
        Assert.assertEquals("/my/test/path", pathAndQuery[0]);
        Assert.assertEquals("?/more/path/stuff/query=test1", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path/myfile.zarr?anonymous=true");
        Assert.assertEquals("/my/test/path/myfile.zarr", pathAndQuery[0]);
        Assert.assertEquals("?anonymous=true", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery(
                "/my/test/path/myfile=?withquestionmark.zarr?anonymous=true");
        Assert.assertEquals("/my/test/path/myfile=?withquestionmark.zarr", pathAndQuery[0]);
        Assert.assertEquals("?anonymous=true", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery(
                "/my/test/path/myfile=?withquestionmark.zarr?profile=test= name");
        Assert.assertEquals("/my/test/path/myfile=?withquestionmark.zarr", pathAndQuery[0]);
        Assert.assertEquals("?profile=test= name", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("?query=test1");
        Assert.assertEquals("", pathAndQuery[0]);
        Assert.assertEquals("?query=test1", pathAndQuery[1]);
    }

    @Test
    public void testRemoveNothing() {

        String[] pathAndQuery = ZarrStore.splitOnQuery("");
        Assert.assertEquals("", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?test-zarr.zarr");
        Assert.assertEquals("/my/test/path?test-zarr.zarr", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path=test?zarr.zarr");
        Assert.assertEquals("/my/test/path=test?zarr.zarr", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("nothing/in/the/middle?=test");
        Assert.assertEquals("nothing/in/the/middle?=test", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("nothing/at/the/end?=");
        Assert.assertEquals("nothing/at/the/end?=", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path.zarr");
        Assert.assertEquals("/my/test/path.zarr", pathAndQuery[0]);
        Assert.assertNull(pathAndQuery[1]);
    }

    @Test
    public void testNormalizePathStripsTrailingSlashes() {
        Assert.assertEquals(
                "s3://host/bucket/image.zarr/0?anonymous=true",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0/?anonymous=true"));

        Assert.assertEquals(
                "s3://host/bucket/image.zarr/0?anonymous=true",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0///?anonymous=true"));

        Assert.assertEquals(
                "s3://host/bucket/image.zarr/0",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0/"));

        Assert.assertEquals(
                "https://host/image.zarr",
                ZarrStore.normalizePath(
                        "https://host/image.zarr/"));

        Assert.assertEquals(
                "s3://host/bucket/image.zarr",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr"));

        Assert.assertEquals(
                "/my/test/path.zarr?anonymous=true",
                ZarrStore.normalizePath(
                        "/my/test/path.zarr?anonymous=true"));
    }
}
