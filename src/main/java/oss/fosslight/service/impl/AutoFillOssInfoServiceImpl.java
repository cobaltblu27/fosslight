/*
 * Copyright (c) 2021 Dongmin Kang
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package oss.fosslight.service.impl;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import oss.fosslight.CoTopComponent;
import oss.fosslight.domain.ProjectIdentification;
import oss.fosslight.repository.OssMapper;
import oss.fosslight.service.AutoFillOssInfoService;
import oss.fosslight.validation.T2CoValidationConfig;

@Service
@Slf4j
public class AutoFillOssInfoServiceImpl extends CoTopComponent implements AutoFillOssInfoService {

	// Mapper
	@Autowired OssMapper ossMapper;
    
    @Override
	public List<ProjectIdentification> checkOssLicenseData(List<ProjectIdentification> componentData, Map<String, String> validMap, Map<String, String> diffMap){
		List<ProjectIdentification> resultData = new ArrayList<ProjectIdentification>();
		Map<String, Object> ruleMap = T2CoValidationConfig.getInstance().getRuleAllMap();

		if(validMap != null) {
			for(String key : validMap.keySet()) {
				if(key.toUpperCase().startsWith("LICENSENAME")
						&& (validMap.get(key).equals(ruleMap.get("LICENSE_NAME.UNCONFIRMED.MSG"))
						|| validMap.get(key).equals(ruleMap.get("LICENSE_NAME.REQUIRED.MSG")))
						|| validMap.get(key).startsWith("Declared")) {
					resultData.addAll((List<ProjectIdentification>) componentData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList()));
				}

				if(key.toUpperCase().startsWith("OSSVERSION") && validMap.get(key).equals(ruleMap.get("OSS_VERSION.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList()));
				}

				if(key.toUpperCase().startsWith("OSSNAME") && validMap.get(key).equals(ruleMap.get("OSS_NAME.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList()));
				}
			}
		}

		if(diffMap != null) {
			for(String key : diffMap.keySet()) {
				if(key.toUpperCase().startsWith("LICENSENAME") && diffMap.get(key).equals(ruleMap.get("LICENSE_NAME.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList()));
				}

				if(key.toUpperCase().startsWith("OSSVERSION") && diffMap.get(key).equals(ruleMap.get("OSS_VERSION.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList()));
				}

				if(key.toUpperCase().startsWith("DOWNLOADLOCATION") && diffMap.get(key).equals(ruleMap.get("DOWNLOAD_LOCATION.DIFFERENT.MSG"))) {
					int duplicateRow = (int) resultData
							.stream()
							.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
							.collect(Collectors.toList())
							.size();

					if(duplicateRow == 0) {
						resultData.addAll((List<ProjectIdentification>) componentData
								.stream()
								.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
								.collect(Collectors.toList()));
					}
				}
			}
		}

		return resultData;
	}
}