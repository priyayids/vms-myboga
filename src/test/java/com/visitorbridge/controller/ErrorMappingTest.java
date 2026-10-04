package com.visitorbridge.controller;

import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.service.BookingService;
import com.visitorbridge.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the error mapping for requests that never reach a controller.
 *
 * <p>Both cases below used to answer 500 because they fell through to the
 * catch-all {@code @ExceptionHandler(Exception.class)}: an unknown path throws
 * {@code NoResourceFoundException}, and a missing required query parameter
 * throws {@code MissingServletRequestParameterException}. A caller mistyping a
 * URL was told the server had broken.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomService roomService;

    @MockBean
    private BookingService bookingService;

    @MockBean
    private NuveqVisitorClient nuveqVisitorClient;

    @Test
    @DisplayName("GET / serves the API index instead of erroring")
    void testRootServesIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("up"))
                .andExpect(jsonPath("$.data.endpoints").isArray());
    }

    @Test
    @DisplayName("Unknown path returns 404, not 500")
    void testUnknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/no/such/endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/no/such/endpoint"));
    }

    @Test
    @DisplayName("Missing required query parameter returns 400, not 500")
    void testMissingQueryParamIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/rooms/1/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("date")));
    }

    @Test
    @DisplayName("Non-numeric path variable returns 400, not 500")
    void testBadPathVariableIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/rooms/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("Malformed JSON body returns 400, not 500")
    void testMalformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/visitors/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
