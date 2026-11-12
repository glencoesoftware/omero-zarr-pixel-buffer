package com.glencoesoftware.omero.zarr;

import static org.junit.jupiter.api.Assertions.*;

import com.glencoesoftware.omero.zarr.ZarrStore.S3Path;
import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.HttpStore;
import dev.zarr.zarrjava.store.S3Store;
import dev.zarr.zarrjava.store.StoreHandle;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

/** Unit test for ZarrStore. */
public class ZarrStoreTest {

    @Test
    public void testS3Path() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(0, v.params.size());
    }

    @Test
    public void testS3PathTrailingSlash() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path/");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(0, v.params.size());
    }

    @Test
    public void testS3PathTrailingSlashes() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path////");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(0, v.params.size());
    }

    @Test
    public void testS3PathWithQuery() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path?test=zarr.zarr");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(Map.of("test", "zarr.zarr"), v.params);
    }

    @Test
    public void testS3PathWithQueryTrailingSlash() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path/?test=zarr.zarr");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(Map.of("test", "zarr.zarr"), v.params);
    }

    @Test
    public void testS3PathWithQueryTrailingSlashes() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path////?test=zarr.zarr");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path", v.key);
        assertEquals(Map.of("test", "zarr.zarr"), v.params);
    }

    @Test
    public void testS3PathWithQuestionMarkAndWithQuery() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path?query=test1?query=test2");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path?query=test1", v.key);
        assertEquals(Map.of("query", "test2"), v.params);
    }

    @Test
    public void testS3PathWithQueryWithForwardSlashes() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path?/more/query=test1");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path?/more/query=test1", v.key);
        assertTrue(v.params.isEmpty());
    }

    @Test
    public void testS3PathAnonymous() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path/myfile.zarr?anonymous=true");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path/myfile.zarr", v.key);
        assertEquals(Map.of("anonymous", "true"), v.params);
    }

    @Test
    public void testS3PathWithQuestionMarkAnonymous() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path/"
                + "myfile=?withquestionmark.zarr?anonymous=true");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path/myfile=?withquestionmark.zarr", v.key);
        assertEquals(Map.of("anonymous", "true"), v.params);
    }

    @Test
    public void testS3PathWithProfileWithEqualsAndSpaces() {
        S3Path v = new S3Path(
                "s3://s3.us-east-1.amazonaws.com/buck/my/test/path/"
                + "myfile=?withquestionmark.zarr?profile=test= name");
        assertEquals("s3.us-east-1.amazonaws.com", v.host);
        assertEquals("buck", v.bucket);
        assertEquals("my/test/path/myfile=?withquestionmark.zarr", v.key);
        assertEquals(Map.of("profile", "test= name"), v.params);
    }

    @Test
    public void testPathAndQueryRegex() {
        Matcher v = S3Path.QUERY_REGEX.matcher("?test=zarr.zarr");
        assertTrue(v.matches());
    }

    @Test
    public void testPathAndQueryRegexNoMatch() {
        Matcher v = S3Path.QUERY_REGEX.matcher("?test");
        assertFalse(v.matches());
    }

    @Test
    public void testRegionFromUriUsEast1() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                new S3Path("s3://s3.us-east-1.amazonaws.com/buck/path").getRegion());
    }

    @Test
    public void testRegionFromUriUsWest2() throws URISyntaxException {
        assertEquals(
                Region.US_WEST_2,
                new S3Path("s3://s3.us-west-2.amazonaws.com/buck/path").getRegion());
    }

    @Test
    public void testRegionFromUriGCS() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                new S3Path("s3://storage.googleapis.com/buck/path").getRegion());
    }

    @Test
    public void testRegionFromUriLivingObjects() throws URISyntaxException {
        assertEquals(
                Region.US_EAST_1,
                new S3Path("s3://livingobjects.ebi.ac.uk/buck/path").getRegion());
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
        assertArrayEquals(new String[] { "bar.zarr/0" }, handle.keys);
    }
}
