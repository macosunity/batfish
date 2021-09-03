package org.batfish.vendor.check_point_management;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.testing.EqualsTester;
import org.apache.commons.lang3.SerializationUtils;
import org.batfish.common.util.BatfishObjectMapper;
import org.junit.Test;

public class NatSettingsTest {
  /**
   * Instance of this class populated with arbitrary values. Useful for generating a valid object
   * for use in tests.
   */
  public static final NatSettings TEST_INSTANCE = new NatSettings(true, "gateway", "All", "hide");
  /** Another test instance, that is not-equal to the previous instance. */
  public static final NatSettings TEST_INSTANCE_DIFFERENT =
      new NatSettings(false, "gateway", "All", "hide");

  @Test
  public void testJacksonDeserialization() throws JsonProcessingException {
    String input =
        "{"
            + "\"GARBAGE\":0,"
            + "\"auto-rule\":true,"
            + "\"hide-behind\":\"gateway\","
            + "\"install-on\":\"All\","
            + "\"method\":\"hide\""
            + "}";
    assertThat(
        BatfishObjectMapper.ignoreUnknownMapper().readValue(input, NatSettings.class),
        equalTo(TEST_INSTANCE));
  }

  @Test
  public void testJavaSerialization() {
    NatSettings obj = TEST_INSTANCE;
    assertEquals(obj, SerializationUtils.clone(obj));
  }

  @Test
  public void testEquals() {
    NatSettings obj = new NatSettings(true, "gateway", "All", "hide");
    new EqualsTester()
        .addEqualityGroup(obj, new NatSettings(true, "gateway", "All", "hide"))
        .addEqualityGroup(new NatSettings(false, "gateway", "All", "hide"))
        .addEqualityGroup(new NatSettings(true, "server", "All", "hide"))
        .addEqualityGroup(new NatSettings(true, "gateway", "None", "hide"))
        .addEqualityGroup(new NatSettings(true, "gateway", "None", "dontHide"))
        .testEquals();
  }
}
