package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.service.IDeviceChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 预置位缓存
 */
@Component
public class PresetDataCatch {

    public static Map<Integer, PresetData> data = new ConcurrentHashMap<>();


    @Autowired
    private DeferredResultHolder deferredResultHolder;


    public void addReady(int sn ) {
        PresetData presetData = data.get(sn);
        if (presetData == null || presetData.getStatus().equals(DataStatus.end)) {
            presetData = new PresetData();
            presetData.setPresetItemList(Collections.synchronizedList(new ArrayList<>()));
            presetData.setSn(sn);
            presetData.setStatus(DataStatus.ready);
            presetData.setLastTime(Instant.now());
            data.put(sn, presetData);
        }
    }

    public void put(int sn, int total, Device device, List<PresetItem> deviceChannelList) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            presetData = new PresetData();
            presetData.setSn(sn);
            presetData.setTotal(total);
            presetData.setPresetItemList(deviceChannelList);
            presetData.setStatus(DataStatus.runIng);
            presetData.setLastTime(Instant.now());
            data.put(sn, presetData);
        }else {
            // 同一个设备的通道同步请求只考虑一个，其他的直接忽略
            if (presetData.getSn() != sn) {
                return;
            }
            presetData.setTotal(total);
            presetData.setStatus(DataStatus.runIng);
            presetData.getPresetItemList().addAll(deviceChannelList);
            presetData.setLastTime(Instant.now());
        }
    }

    public List<PresetItem> get(int sn) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            return null;
        }
        return presetData.getPresetItemList();
    }

    public int getTotal(int sn) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            return 0;
        }
        return presetData.getTotal();
    }

    public SyncStatus getSyncStatus(int sn) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            return null;
        }
        SyncStatus syncStatus = new SyncStatus();
        syncStatus.setCurrent(presetData.getPresetItemList().size());
        syncStatus.setTotal(presetData.getTotal());
        syncStatus.setErrorMsg(presetData.getErrorMsg());
        if (presetData.getStatus().equals(DataStatus.end)) {
            syncStatus.setSyncIng(false);
        }else {
            syncStatus.setSyncIng(true);
        }
        return syncStatus;
    }

    public boolean isSyncRunning(int sn) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            return false;
        }
        return !presetData.getStatus().equals(DataStatus.end);
    }

    @Scheduled(fixedRate = 5 * 1000)   //每5秒执行一次, 发现数据5秒未更新则移除数据并认为数据接收超时
    private void timerTask(){
        Set<Integer> keys = data.keySet();

        Instant instantBefore5S = Instant.now().minusMillis(TimeUnit.SECONDS.toMillis(5));
        Instant instantBefore30S = Instant.now().minusMillis(TimeUnit.SECONDS.toMillis(30));

        for (Integer sn : keys) {
            PresetData presetData = data.get(sn);
            String key = DeferredResultHolder.CALLBACK_CMD_PRESETQUERY + sn;
            if ( presetData.getLastTime().isBefore(instantBefore5S)) {
                // 超过五秒收不到消息任务超时， 只更新这一部分数据, 收到数据与声明的总数一致，则重置通道数据，数据不全则只对收到的数据做更新操作
                if (presetData.getStatus().equals(DataStatus.runIng)) {
                    RequestMessage requestMessage = new RequestMessage();
                    requestMessage.setKey(key);
                    requestMessage.setData(presetData.getPresetItemList());
                    deferredResultHolder.invokeAllResult(requestMessage);

                    String errorMsg = "更新成功，共" + presetData.getTotal() + "条，已更新" + presetData.getPresetItemList().size() + "条";
                    presetData.setErrorMsg(errorMsg);
                }else if (presetData.getStatus().equals(DataStatus.ready)) {
                    String errorMsg = "同步失败，等待回复超时";
                    presetData.setErrorMsg(errorMsg);
                }
                presetData.setStatus(DataStatus.end);
            }
            if (presetData.getStatus().equals(DataStatus.end) && presetData.getLastTime().isBefore(instantBefore30S)) { // 超过三十秒，如果标记为end则删除
                data.remove(sn);
            }
        }
    }


    public void setChannelSyncEnd(int sn, String errorMsg) {
        PresetData presetData = data.get(sn);
        if (presetData == null) {
            return;
        }
        presetData.setStatus(DataStatus.end);
        presetData.setErrorMsg(errorMsg);
    }
}