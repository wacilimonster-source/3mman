package com.m3man.parser;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.m3man.data.model.BaseResult;
import com.m3man.data.model.ProxyModel;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代理抓取
 *
 * @author flymegoc
 * @date 2018/1/20
 */

public class ParseProxy {
    private static final String TAG = ParseProxy.class.getSimpleName();
    /** M95：IPv4 形态校验（四段、每段 1~3 位数字） */
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public static BaseResult<List<ProxyModel>> parseXiCiDaiLi(String html, int page) {
        BaseResult<List<ProxyModel>> baseResult = new BaseResult<>();
        baseResult.setTotalPage(1);
        Document doc = Jsoup.parse(html);

        Element ipList = doc.getElementById("ip_list");
        // M62：源页面结构异常时 ip_list 可能为 null，返回空列表而非 NPE
        Elements trs = ipList == null ? new Elements() : ipList.select("tr");
        int trSize = trs.size();
        List<ProxyModel> proxyModelList = new ArrayList<>();
        for (int i = 0; i < trSize; i++) {
            //第一是标题，跳过
            if (i == 0) {
                continue;
            }
            //tr里的td
            Elements tds = trs.get(i).select("td");
            // M95：列数不足 8 说明是残缺行/表头残留，直接丢弃该行
            if (tds.size() < 8) {
                continue;
            }
            ProxyModel proxyModel = new ProxyModel();
            // M95：行级有效性标记——ip/port 校验不合格时丢弃整行
            boolean validRow = true;
            for (int j = 0; j < tds.size(); j++) {
                Element td = tds.get(j);
                switch (j) {
                    case 0:
                        //国家
                        break;
                    case 1: {
                        //ip
                        String ip = td.text().trim();
                        if (IP_PATTERN.matcher(ip).matches()) {
                            proxyModel.setProxyIp(ip);
                        } else {
                            validRow = false;
                        }
                        break;
                    }
                    case 2: {
                        //端口
                        try {
                            int portValue = Integer.parseInt(td.text().trim());
                            if (portValue >= 1 && portValue <= 65535) {
                                proxyModel.setProxyPort(String.valueOf(portValue));
                            } else {
                                validRow = false;
                            }
                        } catch (NumberFormatException e) {
                            validRow = false;
                        }
                        break;
                    }
                    case 3:
                        //城市
                        break;
                    case 4:
                        //匿名度
                        String anonymous = td.text();
                        proxyModel.setAnonymous(anonymous);
                        break;
                    case 5:
                        //类型 http https socket
                        String type = td.text();
                        if ("http".equalsIgnoreCase(type)) {
                            proxyModel.setType(ProxyModel.TYPE_HTTP);
                        } else if ("https".equalsIgnoreCase(type)) {
                            proxyModel.setType(ProxyModel.TYPE_HTTPS);
                        } else {
                            proxyModel.setType(ProxyModel.TYPE_SOCKS);
                        }
                        break;
                    case 6:
                        //速度
                        break;
                    case 7: {
                        //连接时间
                        // M95：div 可能缺失，判空跳过该字段而非 NPE
                        Element div = td.select("div").first();
                        if (div != null) {
                            proxyModel.setResponseTime(div.attr("title"));
                        }
                        break;
                    }
                    case 8:
                        //存活时间
                        break;
                    case 9:
                        //验证时间
                        break;
                    default:
                }
                if (!validRow) {
                    break;
                }
            }
            // M95：ip/port 不合格的行整行丢弃
            if (!validRow) {
                continue;
            }
            proxyModelList.add(proxyModel);
        }
        baseResult.setData(proxyModelList);
        if (page == 1) {
            // M62：pagination 可能为 null，判空避免 NPE
            Element pagination = doc.getElementsByClass("pagination").first();
            if (pagination != null) {
                Elements elements = pagination.select("a");
                if (elements.size() > 3) {
                    String totalPageStr = elements.get(elements.size() - 2).text();
                    Logger.t(TAG).d(totalPageStr);
                    if (TextUtils.isDigitsOnly(totalPageStr)) {
                        baseResult.setTotalPage(Integer.parseInt(totalPageStr));
                    }
                }
            }
        }
        return baseResult;
    }
}
