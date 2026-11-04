package com.govtech.voicegateway.domain.protection.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.govtech.voicegateway.domain.protection.ProtectionPolicyStore;

/** 저장소는 메모리에 값 하나만 두는 단순한 객체라 실제 객체로 확인한다 */
@WebMvcTest(PolicyController.class)
@Import(ProtectionPolicyStore.class)
class PolicyControllerTest {

	private static final String DEFAULT = """
			{"abuseLimit":5,"afterNotice":"COUNT","afterNoticeLimit":2,"agitationSeconds":5}""";

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("한 번도 저장하지 않았으면 기본값을 돌려준다")
	void getDefault() throws Exception {
		mockMvc.perform(get("/api/v1/policy"))
				.andExpect(status().isOk())
				.andExpect(content().json(DEFAULT, true));
	}

	@Test
	@DisplayName("저장하면 저장한 값을 돌려주고, 이후 조회도 그 값이다")
	void save() throws Exception {
		String policy = """
				{"abuseLimit":3,"afterNotice":"IMMEDIATE","afterNoticeLimit":1,"agitationSeconds":10}""";

		mockMvc.perform(put("/api/v1/policy").contentType(MediaType.APPLICATION_JSON).content(policy))
				.andExpect(status().isOk())
				.andExpect(content().json(policy, true));
		mockMvc.perform(get("/api/v1/policy"))
				.andExpect(content().json(policy, true));
	}

	@Test
	@DisplayName("초기화하면 기본값으로 돌아가고 기본값을 돌려준다")
	void reset() throws Exception {
		mockMvc.perform(put("/api/v1/policy").contentType(MediaType.APPLICATION_JSON).content("""
				{"abuseLimit":3,"afterNotice":"COUNT","afterNoticeLimit":4,"agitationSeconds":10}"""));

		mockMvc.perform(delete("/api/v1/policy"))
				.andExpect(status().isOk())
				.andExpect(content().json(DEFAULT, true));
		mockMvc.perform(get("/api/v1/policy"))
				.andExpect(content().json(DEFAULT, true));
	}

	@Test
	@DisplayName("범위를 벗어나거나 필드가 빠지거나 모르는 정책이면 400이고 정책은 바뀌지 않는다")
	void invalid() throws Exception {
		for (String body : new String[] {
				"""
				{"abuseLimit":11,"afterNotice":"COUNT","afterNoticeLimit":2,"agitationSeconds":5}""",
				"""
				{"abuseLimit":5,"afterNotice":"COUNT","afterNoticeLimit":0,"agitationSeconds":5}""",
				"""
				{"abuseLimit":5,"afterNotice":"COUNT","afterNoticeLimit":2,"agitationSeconds":2}""",
				"""
				{"abuseLimit":5,"afterNotice":"COUNT","afterNoticeLimit":2}""",
				"""
				{"abuseLimit":5,"afterNotice":"LATER","afterNoticeLimit":2,"agitationSeconds":5}"""}) {
			mockMvc.perform(put("/api/v1/policy").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(get("/api/v1/policy"))
				.andExpect(content().json(DEFAULT, true));
	}

	@Test
	@DisplayName("다른 주소의 웹 화면이 브라우저에서 저장할 수 있다 (CORS 사전 요청 허용)")
	void corsPreflight() throws Exception {
		mockMvc.perform(options("/api/v1/policy")
						.header("Origin", "https://web.example.com")
						.header("Access-Control-Request-Method", "PUT")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "*"));
	}
}
