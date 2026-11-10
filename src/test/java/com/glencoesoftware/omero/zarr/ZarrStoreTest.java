package com.glencoesoftware.omero.zarr;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;

import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.HttpStore;
import dev.zarr.zarrjava.store.S3Store;
import dev.zarr.zarrjava.store.StoreHandle;
import software.amazon.awssdk.regions.Region;

/** Unit test for ZarrStore. */
public class ZarrStoreTest {

    @Test
    public void testSplitOnQuery() {
        String[] pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?test=zarr.zarr");
        assertEquals("/my/test/path", pathAndQuery[0]);
        assertEquals("?test=zarr.zarr", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?query=test1?query=test2");
        assertEquals("/my/test/path?query=test1", pathAndQuery[0]);
        assertEquals("?query=test2", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?/more/path/stuff/query=test1");
        assertEquals("/my/test/path", pathAndQuery[0]);
        assertEquals("?/more/path/stuff/query=test1", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path/myfile.zarr?anonymous=true");
        assertEquals("/my/test/path/myfile.zarr", pathAndQuery[0]);
        assertEquals("?anonymous=true", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery(
                "/my/test/path/myfile=?withquestionmark.zarr?anonymous=true");
        assertEquals("/my/test/path/myfile=?withquestionmark.zarr", pathAndQuery[0]);
        assertEquals("?anonymous=true", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery(
                "/my/test/path/myfile=?withquestionmark.zarr?profile=test= name");
        assertEquals("/my/test/path/myfile=?withquestionmark.zarr", pathAndQuery[0]);
        assertEquals("?profile=test= name", pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("?query=test1");
        assertEquals("", pathAndQuery[0]);
        assertEquals("?query=test1", pathAndQuery[1]);
    }

    @Test
    public void testRemoveNothing() {

        String[] pathAndQuery = ZarrStore.splitOnQuery("");
        assertEquals("", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path?test-zarr.zarr");
        assertEquals("/my/test/path?test-zarr.zarr", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path=test?zarr.zarr");
        assertEquals("/my/test/path=test?zarr.zarr", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("nothing/in/the/middle?=test");
        assertEquals("nothing/in/the/middle?=test", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("nothing/at/the/end?=");
        assertEquals("nothing/at/the/end?=", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);

        pathAndQuery = ZarrStore.splitOnQuery("/my/test/path.zarr");
        assertEquals("/my/test/path.zarr", pathAndQuery[0]);
        assertNull(pathAndQuery[1]);
    }

    @Test
    public void testNormalizePathStripsTrailingSlashes() {
        assertEquals(
                "s3://host/bucket/image.zarr/0?anonymous=true",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0/?anonymous=true"));

        assertEquals(
                "s3://host/bucket/image.zarr/0?anonymous=true",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0///?anonymous=true"));

        assertEquals(
                "s3://host/bucket/image.zarr/0",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr/0/"));

        assertEquals(
                "https://host/image.zarr",
                ZarrStore.normalizePath(
                        "https://host/image.zarr/"));

        assertEquals(
                "s3://host/bucket/image.zarr",
                ZarrStore.normalizePath(
                        "s3://host/bucket/image.zarr"));

        assertEquals(
                "/my/test/path.zarr?anonymous=true",
                ZarrStore.normalizePath(
                        "/my/test/path.zarr?anonymous=true"));
    }

    @Test
    public void testRegionFromUriUsEast1() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                ZarrStore.getRegionFromUri(new URI("https://s3.us-east-1.amazonaws.com/")));
    }

    @Test
    public void testRegionFromUriUsWest2() throws URISyntaxException {
        assertEquals(
                Region.US_WEST_2,
                ZarrStore.getRegionFromUri(new URI("https://s3.us-west-2.amazonaws.com/")));
    }

    @Test
    public void testRegionFromUriGCS() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                ZarrStore.getRegionFromUri(new URI("https://storage.googleapis.com")));
    }

    @Test
    public void testRegionFromUriLivingObjects() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                ZarrStore.getRegionFromUri(new URI("https://livingobjects.ebi.ac.uk/")));
    }

    @Test
    public void testFilesystemStore() throws URISyntaxException, ZarrException {
        ZarrStore store = new ZarrStore("/foo/bar.zarr/0");
        StoreHandle handle = store.getStoreHandle();
        assertTrue(handle.store instanceof FilesystemStore);
        assertArrayEquals(new String[] { "foo/bar.zarr/0" }, handle.keys);
    }

    @Test
    public void testFilesystemStoreIllegalScheme()
            throws URISyntaxException, ZarrException {
        assertThrows(IllegalArgumentException.class, () -> {
            new ZarrStore("file:///foo/bar.zarr/0");
        });
    }

    @Test
    public void testHttpStore() throws URISyntaxException, ZarrException {
        ZarrStore store = new ZarrStore("https://foo.com/foo/bar.zarr/0");
        StoreHandle handle = store.getStoreHandle();
        assertTrue(handle.store instanceof HttpStore);
        assertArrayEquals(new String[] { "foo/bar.zarr/0" }, handle.keys);
    }

    @Test
    public void testHttpStoreQueryStringAndFragment()
            throws URISyntaxException, ZarrException {
        ZarrStore store = new ZarrStore(
                "https://foo.com/foo/bar.zarr/0?abc=def#something");
        StoreHandle handle = store.getStoreHandle();
        assertTrue(handle.store instanceof HttpStore);
        assertArrayEquals(new String[] { "foo/bar.zarr/0" }, handle.keys);
    }

    @Test
    public void testS3Store() throws URISyntaxException, ZarrException {
        ZarrStore store = new ZarrStore(
                "s3://s3.us-east-1.amazonaws.com/foo/bar.zarr/0");
        StoreHandle handle = store.getStoreHandle();
        assertTrue(handle.store instanceof S3Store);
        assertArrayEquals(new String[] { "bar.zarr", "0" }, handle.keys);
    }
}
