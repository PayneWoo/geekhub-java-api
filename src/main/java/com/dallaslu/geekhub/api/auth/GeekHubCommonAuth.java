package com.dallaslu.geekhub.api.auth;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.HttpStatus;
import org.apache.http.cookie.Cookie;

import com.dallaslu.geekhub.api.CsrfData;
import com.dallaslu.geekhub.api.GeekHubApi;
import com.dallaslu.geekhub.api.utils.ParseHelper;
import com.dallaslu.utils.captcha.CaptchaResolver;
import com.dallaslu.utils.http.HttpHelper.ResponseResult;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * 普通登录
 */
@Slf4j
@Builder
public class GeekHubCommonAuth implements GeekHubIdentityProvider {
	private String username;
	private String password;
	private String dataPath;
	@Builder.Default
	private boolean loginImmediately = false;
	private final AtomicBoolean busy = new AtomicBoolean(false);

	@Builder.Default
	private CaptchaResolver captchaResolver = new DefaultCaptchaResolver();

	@Override
	public List<Cookie> getNewCookie(GeekHubApi api) {
		if (!busy.compareAndSet(false, true)) {
			return null;
		}
		final List<Cookie> cookies = new ArrayList<>();
		ResponseResult<String> result = api.fetchPage("/users/sign_in", false);
		if (result.isSuccess() && result.getStatus() == HttpStatus.SC_OK) {
			CsrfData csrfData = ParseHelper.parseCsrfData(result.getContent());
			this.captchaResolver.resolve("Login Captcha", "Please input the captcha value in a few minutes", captcha -> {
				if (captcha == null) {
					return false;
				}
				log.info("Starting post sign in form..." + captcha);
				Map<String, Object> param = new HashMap<>();
				param.put(csrfData.getCsrfParam(), csrfData.getCsrfToken());
				param.put("user[login]", this.username);
				param.put("user[password]", this.password);
				param.put("_rucaptcha", captcha);
				param.put("user[remember_me]", "on");
				String responseHtml = api.getHttpHelper().post(api.getWebUrlBase() + "/users/sign_in", param,
						new HashMap<String, String>());
				log.info(responseHtml);
				if (responseHtml.matches("<html><body>You are being <a href=\"" + api.getWebUrlBase()
						+ ".*\">redirected</a>.</body></html>")) {
					// success
					api.setLogon(true);
					List<Cookie> _cookies = api.getHttpHelper().getCookies();
					// 处理 cookie
					writeCookies(api.getHttpHelper().getCookies());
					cookies.addAll(_cookies);
					this.busy.compareAndSet(true, false);
					return true;
				} else {
					return false;
				}
			}, () -> {
				String fileName = UUID.randomUUID().toString() + ".jpg";
				try {
					api.getHttpHelper().download(api.getWebUrlBase() + "/rucaptcha/", fileName,
							dataPath + File.separator + "tmp/");
				} catch (Exception e) {
					e.printStackTrace();
				}
				File captchaImage = new File(dataPath + File.separator + "tmp/" + File.separator + fileName);
				return captchaImage;
			});
		}
		while (cookies.isEmpty()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
				break;
			}
		}
		this.busy.compareAndSet(true, false);
		return null;
	}

	private List<Cookie> loadCookies() {
		try {
			File cookiesFile = new File(this.dataPath + File.separator + username + ".data");
			if (cookiesFile.exists()) {
				@SuppressWarnings("resource")
				ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cookiesFile));
				@SuppressWarnings("unchecked")
				List<Cookie> cookies = (List<Cookie>) ois.readObject();
				return cookies;
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}

	private void writeCookies(List<Cookie> cookies) {
		try {
			@SuppressWarnings("resource")
			ObjectOutputStream oos = new ObjectOutputStream(
					new FileOutputStream(new File(this.dataPath + File.separator + username + ".data")));
			for (Cookie c : cookies) {
				log.info(c.getName() + "=" + c.getValue());
			}
			oos.writeObject(cookies);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isBusy() {
		return busy.get();
	}

	@Override
	public List<Cookie> loadCookie(GeekHubApi geekHubApi) {
		List<Cookie> cookies = loadCookies();
		if (cookies != null && !cookies.isEmpty()) {
			return cookies;
		}
		
		if(loginImmediately) {
			return getNewCookie(geekHubApi);
		}
		return null;
	}
}
