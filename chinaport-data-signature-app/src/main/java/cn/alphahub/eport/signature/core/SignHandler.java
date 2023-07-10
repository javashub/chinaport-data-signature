package cn.alphahub.eport.signature.core;

import cn.alphahub.eport.signature.config.UkeyInitialConfig;
import cn.alphahub.eport.signature.config.UkeyProperties;
import cn.alphahub.eport.signature.entity.*;
import cn.alphahub.eport.signature.entity.UkeyResponse.Args;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;


/**
 * <p>电子口岸加签业务核心类</p>
 * <a href='http://tool.qdhuaxun.cn/?signdoc'>华讯云业务帮助文档，签名的不要看，本项目搞定了所有的签名，华讯的文档可以参考，不推荐购买</a>
 *
 * @author weasley
 * @version 1.0
 * @date 2022/2/12
 */
@Slf4j
@Service
@Validated
public class SignHandler {
    @Resource
    private UkeyProperties ukeyProperties;

    @Resource
    private CertificateHandler certificateHandler;

    @Resource
    private WebSocketClientHandler webSocketClientHandler;

    @Resource
    private StandardWebSocketClient standardWebSocketClient;

    /**
     * 获取ukey的socket入参的inData字段
     *
     * @param request 加签数据请求入参
     * @return ukey的socket入参的inData字段
     */
    public static String getInitData(@Valid SignRequest request) {
        if (isSignXml(request)) {
            return SignatureHandler.getSignatureValueBeforeSend(request);
        }
        return request.getData();
    }

    /**
     * 判断是否总署xml
     *
     * @param request 加签数据请求入参
     * @return 总署xml返回true
     */
    public static boolean isSignXml(@Valid SignRequest request) {
        if (StringUtils.isBlank(request.getData())) {
            throw new IllegalArgumentException("加签数据请求入参能为空!");
        }
        boolean isXmlSignString = StringUtils.startsWith(request.getData(), "<ceb:CEB") || StringUtils.startsWith(request.getData(), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        boolean is197SignString = StringUtils.startsWith(request.getData(), "\"sessionID\"");
        if (isXmlSignString && !is197SignString) {
            return true;
        }
        if (is197SignString && !isXmlSignString) {
            return false;
        }
        throw new IllegalArgumentException("加签数据请求入参不合法,请检查参数:" + request.getData());
    }

    /**
     * 如果.cer证书不存在时动态切换发送u-key的签名方法
     *
     * @return 发送u-key的签名的入参
     * @apiNote 待验证2022-07后加签xml报文
     * @since 2022-11-27
     */
    public String getDynamicSignDataParameter(@Valid SignRequest request) {
        if (certificateHandler.getUkeyValidTimeBegin().isAfter(CertificateHandler.DATE_TIME_202207)) {
            //1. 2022-07-01以后签发的u-key都使用这个签名方式
            return UkeyInitialConfig.getSignDataAsPEM(request);
        }
        return certificateHandler.getCertExists().equals(true) ? UkeyInitialConfig.getSignDataAsPEM(request) : UkeyInitialConfig.getSignDataNoHashAsPEMP(request);
    }

    /**
     * WebSocket发送消息给电子口岸u-key
     *
     * @param request 加签请求参数
     * @param payload websocket发送的数据载荷
     */
    public SignResult sign(@Valid SignRequest request, @NotBlank(message = "websocket发送的数据载荷不能为空") String payload) {
        log.info("收到u-key加签数据: {}", payload);

        WebSocketWrapper wrapper = webSocketClientHandler.getWebSocketWrapper();
        wrapper.setPayload(payload);
        wrapper.setRequest(request);
        wrapper.setSignResult(new SignResult());
        wrapper.setThreadReference(new AtomicReference<>(Thread.currentThread()));
        if (SignHandler.isSignXml(request)) {
            wrapper.getSignResult().setDigestValue(SignatureHandler.getDigestValueOfCEBXxxMessage(request.getData()));
        }

        WebSocketConnectionManager manager = new WebSocketConnectionManager(standardWebSocketClient, webSocketClientHandler, ukeyProperties.getWsUrl());
        manager.start();

        try {
            LockSupport.parkNanos(wrapper.getThreadReference().get(), 1000 * 1000 * 1000 * 5L);
        } catch (Exception e) {
            log.error("线程自动unpark异常 {}", e.getLocalizedMessage(), e);
            wrapper.getSignResult().setSuccess(false);
        } finally {
            manager.stop();
        }

        return wrapper.getSignResult();
    }

    /**
     * 获取u-key加签返回内层对象（我们要的数据在这里面）
     */
    public Args getUkeyResponseArgs(UkeyRequest ukeyRequest) {
        AtomicReference<UkeyResponseArgsWrapper> reference = new AtomicReference<>();
        reference.set(new UkeyResponseArgsWrapper(Thread.currentThread(), new Args()));
        WebSocketConnectionManager certValidTimeManager = new WebSocketConnectionManager(standardWebSocketClient, new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(JSONUtil.toJsonStr(ukeyRequest)));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                UkeyResponse response = JSONUtil.toBean(message.getPayload(), new TypeReference<>() {
                }, true);
                try {
                    if (Objects.equals(response.get_id(), ukeyRequest.get_id())) {
                        log.warn("从电子口岸ukey中获取获取到数据: {}", response.get_args());
                        reference.get().setResponseArgs(response.get_args());
                    }
                } catch (Exception e) {
                    log.error("唤醒线程异常 {}", e.getLocalizedMessage(), e);
                }
            }
        }, ukeyProperties.getWsUrl());
        certValidTimeManager.start();
        try {
            LockSupport.parkNanos(reference.get().getThread(), 1000 * 1000 * 1000 * 1L);
        } catch (Exception e) {
            log.error("线程自动unpark异常 {}", e.getLocalizedMessage(), e);
        } finally {
            certValidTimeManager.stop();
        }
        return reference.get().getResponseArgs();
    }

}
