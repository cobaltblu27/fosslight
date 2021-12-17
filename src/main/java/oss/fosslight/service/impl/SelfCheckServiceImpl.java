/*
 * Copyright (c) 2021 LG Electronics Inc.
 * SPDX-License-Identifier: AGPL-3.0-only 
 */

package oss.fosslight.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.File;
import java.nio.file.Files;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import oss.fosslight.CoTopComponent;
import oss.fosslight.common.CoCodeManager;
import oss.fosslight.common.CoConstDef;
import oss.fosslight.common.CommonFunction;
import oss.fosslight.domain.BinaryMaster;
import oss.fosslight.domain.CoMail;
import oss.fosslight.domain.CoMailManager;
import oss.fosslight.domain.History;
import oss.fosslight.domain.LicenseMaster;
import oss.fosslight.domain.OssComponents;
import oss.fosslight.domain.OssComponentsLicense;
import oss.fosslight.domain.OssLicense;
import oss.fosslight.domain.OssMaster;
import oss.fosslight.domain.OssNotice;
import oss.fosslight.domain.PartnerMaster;
import oss.fosslight.domain.Project;
import oss.fosslight.domain.ProjectIdentification;
import oss.fosslight.domain.T2File;
import oss.fosslight.domain.UploadFile;
import oss.fosslight.domain.Vulnerability;
import oss.fosslight.repository.FileMapper;
import oss.fosslight.repository.LicenseMapper;
import oss.fosslight.repository.PartnerMapper;
import oss.fosslight.repository.ProjectMapper;
import oss.fosslight.repository.SelfCheckMapper;
import oss.fosslight.repository.T2UserMapper;
import oss.fosslight.repository.CommentMapper;
import oss.fosslight.service.FileService;
import oss.fosslight.service.OssService;
import oss.fosslight.service.SelfCheckService;
import oss.fosslight.service.VerificationService;
import oss.fosslight.util.StringUtil;

import org.apache.commons.io.FilenameUtils;
import oss.fosslight.util.FileUtil;
import oss.fosslight.util.ExcelDownLoadUtil;
import oss.fosslight.util.SPDXUtil2;
import org.jsoup.Jsoup;
import org.apache.commons.lang.StringEscapeUtils;
import oss.fosslight.domain.CommentsHistory;
import oss.fosslight.service.CommentService;
import oss.fosslight.util.DateUtil;
import org.jsoup.nodes.Document;
import java.util.LinkedHashMap;


@Service
@Slf4j
public class SelfCheckServiceImpl extends CoTopComponent implements SelfCheckService {
	//SERVICE
	@Autowired FileService fileService;
	@Autowired OssService ossService;
	@Autowired VerificationService verificationService;
	@Autowired CommentService commentService;	

	//MAPPER
	@Autowired ProjectMapper projectMapper;
	@Autowired T2UserMapper userMapper;
	@Autowired PartnerMapper partnerMapper;
	@Autowired FileMapper fileMapper;
	@Autowired SelfCheckMapper selfCheckMapper;
	@Autowired LicenseMapper licenseMapper;
	@Autowired CommentMapper commentMapper;
	

	private static String NOTICE_PATH = CommonFunction.emptyCheckProperty("notice.path", "/notice");
	private static String EXPORT_TEMPLATE_PATH = CommonFunction.emptyCheckProperty("export.template.path", "/template");
	
	@Override
	public Map<String, Object> getProjectList(Project project) {
		HashMap<String, Object> map = new HashMap<String, Object>();
		List<Project> list = null;

		try {
			int records = selfCheckMapper.selectProjectTotalCount(project);
			project.setTotListSize(records);

			String ossId = project.getOssId();

			if (!StringUtil.isEmpty(ossId)) {
				list = selfCheckMapper.selectUnlimitedOssComponentBomList(project);
			} else {
				list = selfCheckMapper.selectProjectList(project);
				
				if(list != null) {
					// 코드변환처리
					for(Project bean : list) {
						// DISTRIBUTION Android Flag
						// 이슈로 인해 android project가 좀더 세분화 되어 android project기준이 변경됨. 단 selfcheck에서는 해당 기준이 필요 없음으로 판단이 되어 주석처리함.
						// DISTRIBUTION_TYPE
						bean.setDistributionType(CoCodeManager.getCodeString(CoConstDef.CD_DISTRIBUTION_TYPE, bean.getDistributionType()));
						// Project Status
						bean.setStatus( CoCodeManager.getCodeString(CoConstDef.CD_PROJECT_STATUS, bean.getStatus()));
						// Identification Status
						bean.setIdentificationStatus(CoCodeManager.getCodeString(CoConstDef.CD_IDENTIFICATION_STATUS, bean.getIdentificationStatus()));
						// Verification Status
						bean.setVerificationStatus(CoCodeManager.getCodeString(CoConstDef.CD_IDENTIFICATION_STATUS, bean.getVerificationStatus()));
						// Distribute Status
						bean.setDestributionStatus(CoCodeManager.getCodeString(CoConstDef.CD_DISTRIBUTE_STATUS, bean.getDestributionStatus()));
						// DIVISION
						bean.setDivision(CoCodeManager.getCodeString(CoConstDef.CD_USER_DIVISION, bean.getDivision()));
						
						//OS_TYPE
						if("999".equals(bean.getOsType())){
							bean.setOsType(bean.getOsTypeEtc());
						}else{
							bean.setOsType(CoCodeManager.getCodeString(CoConstDef.CD_OS_TYPE, bean.getOsType()));
						}
					}
				}
			}

			map.put("page", project.getCurPage());
			map.put("total", project.getTotBlockSize());
			map.put("records", records);
			map.put("rows", list);
		} catch (Exception e) {
			log.debug(e.getMessage());
		}

		return map;
	}
	
	@Override
	public Project getProjectDetail(Project project) {
		// master
		project = selfCheckMapper.selectProjectMaster(project);
		
		project.setDestributionName(CoCodeManager.getCodeString(CoConstDef.CD_DISTRIBUTION_TYPE, project.getDistributionType()));
		//OS_TYPE
		if(!"999".equals(project.getOsType())){
			project.setOsTypeEtc(CoCodeManager.getCodeString(CoConstDef.CD_OS_TYPE, project.getOsType()));
		}

		// watcher
		List<Project> watcherList = selfCheckMapper.selectWatchersList(project);
		project.setWatcherList(watcherList);

		// file
		project.setCsvFile(selfCheckMapper.selectCsvFile(project));
		
		return project;
	}
	
	@Override
	public Map<String, Object> getIdentificationGridList(ProjectIdentification identification) {
		HashMap<String, Object> map = new HashMap<String, Object>();
		List<ProjectIdentification> list = null;
		identification.setRoleOutLicense(CoCodeManager.CD_ROLE_OUT_LICENSE);
		
		boolean isLoadFromProject = isEmpty(identification.getReferenceId()) && !isEmpty(identification.getRefPrjId());
		
		if(isLoadFromProject) {
			identification.setReferenceId(identification.getRefPrjId());
		}
		
		boolean isApplyFromBat = isEmpty(identification.getReferenceId()) && !isEmpty(identification.getRefBatId());
		
		if(isApplyFromBat) {
			identification.setReferenceId(identification.getRefBatId());
		}
			
		HashMap<String, Object> subMap = new HashMap<String, Object>();
			
		list = selfCheckMapper.selectIdentificationGridList(identification);
		if(list != null && !list.isEmpty()) {
			
			ProjectIdentification param = new ProjectIdentification();
			param.setReferenceDiv(identification.getReferenceDiv());
			param.setReferenceId(identification.getReferenceId());
			OssMaster ossParam = new OssMaster();
			
			// components license 정보를 한번에 가져온다
			for(ProjectIdentification bean : list) {
				param.addComponentIdList(bean.getComponentId());
				
				if(!isEmpty(bean.getOssId())) {
					ossParam.addOssIdList(bean.getOssId());
				}
				
				// oss Name은 작성하고, oss Version은 작성하지 않은 case경우 해당 분기문에서 처리
				if(isEmpty(bean.getCveId()) 
						&& isEmpty(bean.getOssVersion()) 
						&& !isEmpty(bean.getCvssScoreMax())
						&& !("-".equals(bean.getOssName()))){ 
					String[] cvssScoreMax = bean.getCvssScoreMax().split("\\@");
					bean.setCvssScore(cvssScoreMax[0]);
					bean.setCveId(cvssScoreMax[1]);
				}
			}
				
			// oss id로 oss master에 등록되어 있는 라이선스 정보를 취득
			Map<String, OssMaster> componentOssInfoMap = null;
			
			if(ossParam.getOssIdList() != null && !ossParam.getOssIdList().isEmpty()) {
				componentOssInfoMap = ossService.getBasicOssInfoListById(ossParam);
			}
				
			List<ProjectIdentification> licenseList = selfCheckMapper.identificationSubGrid(param);
			
			for(ProjectIdentification licenseBean : licenseList) {
				for(ProjectIdentification bean : list) {
					if(licenseBean.getComponentId().equals(bean.getComponentId())) {
						// 수정가능 여부 초기설정
						licenseBean.setEditable(CoConstDef.FLAG_YES);
						bean.addComponentLicenseList(licenseBean);
						break;
					}
				}
			}

			// license 정보 등록
			for(ProjectIdentification bean : list) {
				if(bean.getComponentLicenseList()!=null){
					String licenseCopy = "";
						
					// multi dual 라이선스의 경우, main row에 표시되는 license 정보는 OSS List에 등록되어진 라이선스를 기준으로 표시한다.
					// ossId가 없는 경우는 기본적으로 subGrid로 등록될 수 없다
					// 이짓거리를 하는 두번째 이유는, subgrid 에서 사용자가 추가한 라이선스와 oss 에 등록되어 있는 라이선스를 구분하기 위함
					if(componentOssInfoMap == null) {
						componentOssInfoMap = new HashMap<>();
					}
					
					OssMaster ossBean = componentOssInfoMap.get(bean.getOssId());
						
					if(ossBean != null
							&& CoConstDef.LICENSE_DIV_MULTI.equals(ossBean.getLicenseDiv())
							&& ossBean.getOssLicenses() != null && !ossBean.getOssLicenses().isEmpty()) {
						for(OssLicense ossLicenseBean : ossBean.getOssLicenses()) {
							if(!isEmpty(ossLicenseBean.getOssCopyright())) {
								licenseCopy += (!isEmpty(licenseCopy) ? "\r\n" : "") + ossLicenseBean.getOssCopyright();
							}
								
							//삭제 불가 처리
							for(ProjectIdentification licenseBean : bean.getComponentLicenseList()) {
								// license index 까지 비교하는 이유는
								// multi dual 혼용인 경우, 동일한 라이선스가 두번 등록 될 수 있기 때문
								if(ossLicenseBean.getLicenseId().equals(licenseBean.getLicenseId()) 
										&& ossLicenseBean.getOssLicenseIdx().equals(licenseBean.getRnum())) {
									licenseBean.setEditable(CoConstDef.FLAG_NO);
									break;
								}
							}
						}
						
						bean.setLicenseName(CommonFunction.makeLicenseExpressionIdentify(bean.getComponentLicenseList(), ","));
					} else {
						// license text는 표시하지 않기 때문에 설정할 필요는 없음
						for(ProjectIdentification licenseBean : bean.getComponentLicenseList()) {
							if(!isEmpty(licenseBean.getCopyrightText())) {
								licenseCopy += (!isEmpty(licenseCopy) ? "\r\n" : "") + licenseBean.getCopyrightText();
							}
						}
						
						bean.setLicenseName(CommonFunction.makeLicenseExpressionIdentify(bean.getComponentLicenseList(), ","));
					}
					
					bean.setLicenseNameExistsYn(CommonFunction.existsLicenseName(bean.getComponentLicenseList()) ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
					bean.setLicenseUserGuideStr(CommonFunction.checkLicenseUserGuide(bean.getComponentLicenseList()));
					bean.setLicenseUserGuideYn(isEmpty(bean.getLicenseUserGuideStr()) ? CoConstDef.FLAG_NO : CoConstDef.FLAG_YES);
					bean.setRestriction(CommonFunction.setLicenseRestrictionList(bean.getComponentLicenseList()));
					
					// subGrid의 Item 추출을 위해 별도의 map으로 구성한다.
					// 부몬의 component_id를 key로 관리한다.
					subMap.put(bean.getGridId(), bean.getComponentLicenseList());
				}	
			}
			
			// 다른 프로젝트에서 load한 경우 component id 초기화
			if(isLoadFromProject || isApplyFromBat) {
				subMap = new HashMap<>();

				// refproject id + "p" + componentid 로 component_id를 재생성 하고, 
				// license 의 경우 재성생한 component_id + 기존 license grid_id의 component_license_id 부분을 결합
				for(ProjectIdentification bean : list) {
					if(isLoadFromProject) {
						bean.setRefPrjId(identification.getRefPrjId());
					} else if(isApplyFromBat) {
						bean.setRefBatId(identification.getRefBatId());
					}
						
					String _compId = CoConstDef.GRID_NEWROW_DEFAULT_PREFIX;
					
					if(isLoadFromProject) {
						_compId += identification.getRefPrjId();
					} else if (isApplyFromBat) {
						_compId += identification.getRefBatId();
					}
					
					_compId += "p" + bean.getComponentId();
					
					bean.setComponentId("");
					bean.setGridId(_compId);

					if(bean.getComponentLicenseList()!=null){
						for(ProjectIdentification licenseBean : bean.getComponentLicenseList()) {
							licenseBean.setComponentId("");
							licenseBean.setGridId(_compId + "-"+ licenseBean.getComponentLicenseId());
						}
					}
					
					subMap.put(bean.getGridId(), bean.getComponentLicenseList());
				}
			}
		}
			
		// 편집중인 data 가 존재할 경우 append 한다.
		{
			if(identification.getMainDataGridList() != null) {
				for(ProjectIdentification bean : identification.getMainDataGridList()) {
					//멀티라이센스일 경우
					if(CoConstDef.LICENSE_DIV_MULTI.equals(bean.getLicenseDiv())){
						for (List<ProjectIdentification> comLicenseList : identification.getSubDataGridList()) {
							for (ProjectIdentification comLicense : comLicenseList) {
								if(bean.getComponentId().equals(comLicense.getComponentId())){
									bean.addComponentLicenseList(comLicense);
								}
							}
						}
					} else { //싱글라이센스일경우
						ProjectIdentification license = new ProjectIdentification();
						license.setComponentId(bean.getComponentId());
						license.setLicenseId(bean.getLicenseId());
						license.setLicenseName(bean.getLicenseName());
						license.setLicenseText(bean.getLicenseText());
						license.setCopyrightText(bean.getCopyrightText());
						license.setExcludeYn(bean.getExcludeYn());
						bean.addComponentLicenseList(license);
					}
				}

				for(ProjectIdentification bean : identification.getMainDataGridList()) {
					list.add(0, bean);
					subMap.put(bean.getGridId(), bean.getComponentLicenseList());
				}
			}
		}
			
		// exclude row의 재정렬 (가장 마지막으로)
		List<ProjectIdentification> newSortList = new ArrayList<>();
		List<ProjectIdentification> excludeList = new ArrayList<>();
		
		for(ProjectIdentification bean : list) {
			if(CoConstDef.FLAG_YES.equals(bean.getExcludeYn())) {
				excludeList.add(bean);
			} else {
				newSortList.add(bean);
			}
		}
			
		newSortList.addAll(excludeList);
		list = newSortList;
		
		map.put("subData", subMap);
		map.put("mainData", list);

		return map;
	}
	
	@Transactional
	@Override
	@CacheEvict(value="autocompleteProjectCache", allEntries=true)
	public void registProject(Project project) {
		boolean isNew = isEmpty(project.getPrjId());
			
		// admin이 아니라면 creator를 변경하지 않는다.
		if(!CommonFunction.isAdmin()) {
			project.setCreator(null);
		}
			
		// project master
		selfCheckMapper.insertProjectMaster(project);

		// oss notice
		OssNotice noticeParam = new OssNotice();
		noticeParam.setPrjId(project.getPrjId());
		noticeParam.setNoticeType(avoidNull(project.getNoticeType(), CoConstDef.CD_DTL_NOTICE_TYPE_GENERAL));

		if(CoConstDef.CD_NOTICE_TYPE_PLATFORM_GENERATED.equals(project.getNoticeType())) {
			noticeParam.setNoticeTypeEtc(project.getNoticeTypeEtc());
		}

		selfCheckMapper.makeOssNotice(noticeParam);

		// project watcher insert
		ArrayList<Map<String, String>> divisionList = new ArrayList<Map<String, String>>();
		ArrayList<Map<String, String>> emailList = new ArrayList<Map<String, String>>();

		if(isNew) {
			if (project.getWatchers()!= null) {
				String[] arr;
				for (String watcher : project.getWatchers()) {
					Map<String, String> m = new HashMap<String, String>();
					arr = watcher.split("\\/");
					
					if(!"Email".equals(arr[1])){
						project.setPrjDivision(arr[0]);
						
						if(arr.length > 1){
							project.setPrjUserId(arr[1]);
						}else{
							project.setPrjUserId("");
						}
						
						project.setPrjEmail("");
						
						m.put("division", project.getPrjDivision());
						m.put("userId", project.getPrjUserId());
						
						divisionList.add(m);
					}else{
						project.setPrjDivision("");
						project.setPrjUserId("");
						project.setPrjEmail(arr[0]);
						
						m.put("email", project.getPrjEmail());
						
						emailList.add(m);
					}
					
					List<Project> watcherList = selfCheckMapper.selectWatchersCheck(project);
					
					if(watcherList.size() == 0){
						selfCheckMapper.insertProjectWatcher(project);						
					}
				}
			}
			
			project.setDivisionList(divisionList);
			project.setEmailList(emailList);
			
			selfCheckMapper.deleteProjectWatcher(project);
		}
	}
	
	@Transactional
	@Override
	@CacheEvict(value="autocompleteProjectCache", allEntries=true)
	public void deleteProject(Project project) {
		// project master
		selfCheckMapper.deleteProjectMaster(project);
	}
	
	@Override
	@Transactional
	public void registSrcOss(List<ProjectIdentification> ossComponent, List<List<ProjectIdentification>> ossComponentLicense, Project project) {
		registSrcOss(ossComponent, ossComponentLicense, project, CoConstDef.CD_DTL_SELF_COMPONENT_ID);
	}
	
	@Override
	@Transactional
	public void registSrcOss(List<ProjectIdentification> ossComponent,
			List<List<ProjectIdentification>> ossComponentLicense, Project project, String refDiv) {
		// 컴포넌트 마스터 라이센스 지우기
		ProjectIdentification prj = new ProjectIdentification();
		prj.setReferenceId(project.getPrjId());
		prj.setReferenceDiv(refDiv);
		List<OssComponents> componentId = selfCheckMapper.selectComponentId(prj);
		
		for (int i = 0; i < componentId.size(); i++) {
			selfCheckMapper.deleteOssComponentsLicense(componentId.get(i));
		}
				
		// 한건도 없을시 프로젝트 마스터 SRC 사용가능여부가 N이면 N 그외 null
		if(ossComponent.size()==0){
			Project projectSubStatus = new Project();
			projectSubStatus.setPrjId(project.getPrjId());
			projectSubStatus.setModifier(projectSubStatus.getLoginUserName());
			projectSubStatus.setReferenceDiv(refDiv);
			
			selfCheckMapper.updateProjectMaster(projectSubStatus);
		}
		
		ossComponent = convertOssNickName(ossComponent);
		ossComponentLicense = convertLicenseNickName(ossComponentLicense);
		
		//deleteRows
		List<String> deleteRows = new ArrayList<String>();

		// 컴포넌트 등록	
		for (int i = 0; i < ossComponent.size(); i++) {
			String downloadLocation = ossComponent.get(i).getDownloadLocation();
			
			if(downloadLocation.endsWith("/")) {
				ossComponent.get(i).setDownloadLocation(downloadLocation.substring(0, downloadLocation.length()-1));
			}
			
			//update
			if(!StringUtil.contains(ossComponent.get(i).getGridId(), CoConstDef.GRID_NEWROW_DEFAULT_PREFIX)){
				//ossComponents 등록
				selfCheckMapper.updateSrcOssList(ossComponent.get(i));
				
				deleteRows.add(ossComponent.get(i).getComponentIdx());
				
				//멀티라이센스일 경우
				if(CoConstDef.LICENSE_DIV_MULTI.equals(ossComponent.get(i).getLicenseDiv())){
					for (List<ProjectIdentification> comLicenseList : ossComponentLicense) {
						for (ProjectIdentification comLicense : comLicenseList) {
							if(ossComponent.get(i).getComponentId().equals(comLicense.getComponentId())){
								OssComponentsLicense license = CommonFunction.reMakeLicenseBean(comLicense, CoConstDef.LICENSE_DIV_MULTI);
								
								// 라이센스 등록
								selfCheckMapper.registComponentLicense(license);
							}
						}
					}
				} else { //싱글라이센스일경우
					OssComponentsLicense license = CommonFunction.reMakeLicenseBean(ossComponent.get(i), CoConstDef.LICENSE_DIV_SINGLE);
					// 라이센스 등록
					selfCheckMapper.registComponentLicense(license);
				}
			} else { // insert
				//ossComponents 등록
				String exComponentId = ossComponent.get(i).getGridId();
				//component_idx key
				String componentIdx = selfCheckMapper.selectComponentIdx(prj);
				ossComponent.get(i).setReferenceId(project.getPrjId());
				ossComponent.get(i).setReferenceDiv(refDiv);
				ossComponent.get(i).setComponentIdx(componentIdx);
				String _componentId = ossComponent.get(i).getReferenceId() + "-" + ossComponent.get(i).getReferenceDiv() + "-" + ossComponent.get(i).getComponentIdx();
				
				selfCheckMapper.insertSrcOssList(ossComponent.get(i));
				deleteRows.add(componentIdx);
				
				//멀티라이센스일 경우
				if(CoConstDef.LICENSE_DIV_MULTI.equals(ossComponent.get(i).getLicenseDiv())){
					for (List<ProjectIdentification> comLicenseList : ossComponentLicense) {
						for (ProjectIdentification comLicense : comLicenseList) {
							// null point
							if(isEmpty(comLicense.getGridId())) {
								continue;
							}
							
							String gridId = comLicense.getGridId().split("-")[0];
							
							if(exComponentId.equals(comLicense.getComponentId())
									|| exComponentId.equals(gridId)){
								OssComponentsLicense license = CommonFunction.reMakeLicenseBean(comLicense, CoConstDef.LICENSE_DIV_MULTI);
								// 컴포넌트 ID 설정
								license.setComponentId(_componentId);
								
								selfCheckMapper.registComponentLicense(license);
							}
						}
					}
				} else { //싱글라이센스일경우
					OssComponentsLicense license = CommonFunction.reMakeLicenseBean(ossComponent.get(i), CoConstDef.LICENSE_DIV_SINGLE);
					// 라이센스 등록
					license.setComponentId(_componentId);
					
					selfCheckMapper.registComponentLicense(license);
				}
			}
		}
		
		{
			Project _ossidUpdateParam = new Project();
			_ossidUpdateParam.setPrjId(project.getPrjId());
			_ossidUpdateParam.setReferenceDiv(refDiv);
			selfCheckMapper.updateComponentsOssId(_ossidUpdateParam);
			
			// license id 등록
			selfCheckMapper.updateComponentsLicenseId(_ossidUpdateParam);
		}
		
		//delete
		OssComponents param = new OssComponents();
		param.setReferenceDiv(refDiv);
		param.setReferenceId(project.getPrjId());
		param.setOssComponentsIdList(deleteRows);
		
		selfCheckMapper.deleteOssComponentsWithIds(param);
		
		// 파일 등록
		if(!isEmpty(project.getSrcCsvFileId()) || !isEmpty(project.getSrcAndroidCsvFileId()) || !isEmpty(project.getSrcAndroidNoticeFileId()) || !isEmpty(project.getBinCsvFileId()) || !isEmpty(project.getBinBinaryFileId())){
			selfCheckMapper.updateFileId(project);
			
			if(project.getCsvFile() != null) {
				for (int i = 0; i < project.getCsvFile().size(); i++) {
					fileService.deletePhysicalFile(project.getCsvFile().get(i), "SELF");
					selfCheckMapper.deleteFileBySeq(project.getCsvFile().get(i));
				}		
				
			}
			if(project.getCsvAddFileSeq() != null) {
				for (int i = 0; i < project.getCsvAddFileSeq().size(); i++) {
					selfCheckMapper.updateFileBySeq(project.getCsvAddFileSeq().get(i));
				}				
			}
		}
		
		{
			// vulnerability max score를 저장
			double max_cvss_score = 0;
			String max_vuln_ossName = null;
			String max_vuln_ossVersion = null;
			List<ProjectIdentification> _ossList = selfCheckMapper.selectIdentificationGridList(prj);
			
			if(_ossList != null) {
				for(ProjectIdentification targetBean : _ossList) {
					if(targetBean != null && !CoConstDef.FLAG_YES.equals(targetBean.getExcludeYn()) && !isEmpty(targetBean.getCvssScore())) {
						double _currentSccore = Double.parseDouble(targetBean.getCvssScore());
						
						if(Double.compare(_currentSccore, max_cvss_score) > 0) {
							max_cvss_score = _currentSccore;
							max_vuln_ossName = targetBean.getOssName();
							max_vuln_ossVersion = targetBean.getOssVersion();
						}
					}
				}
			}
			
			Project vnlnUpdBean = new Project();
			
			if(!isEmpty(max_vuln_ossName)) {
				vnlnUpdBean.setOssName(max_vuln_ossName);
				vnlnUpdBean.setOssVersion(avoidNull(max_vuln_ossVersion));
				vnlnUpdBean = selfCheckMapper.getMaxVulnByOssName(vnlnUpdBean);				
			}
			
			vnlnUpdBean.setUpdVuln(CoConstDef.FLAG_YES);
			vnlnUpdBean.setPrjId(project.getPrjId());
			vnlnUpdBean.setModifier(vnlnUpdBean.getLoginUserName());
			
			selfCheckMapper.updateProjectMaster(vnlnUpdBean);
		}
	}
	
	private List<ProjectIdentification> convertOssNickName(List<ProjectIdentification> ossComponentList) {
		List<String> ossCheckParam = new ArrayList<>();
		List<OssMaster> ossNickNameList = null;
		Map<String, OssMaster> ossNickNameConvertMap = new HashMap<>();
		
		for(ProjectIdentification bean : ossComponentList) {
			String _ossName = avoidNull(bean.getOssName()).trim();
			
			if(!isEmpty(_ossName) && !"-".equals(_ossName) && !ossCheckParam.contains(_ossName)) {
				ossCheckParam.add(_ossName);
			}
		}
		if(!ossCheckParam.isEmpty()) {
			OssMaster param = new OssMaster();
			
			param.setOssNames(ossCheckParam.toArray(new String[ossCheckParam.size()]));
			ossNickNameList = projectMapper.checkOssNickName(param);
			
			if(ossNickNameList != null) {
				for(OssMaster bean : ossNickNameList) {
					ossNickNameConvertMap.put(bean.getOssNickname().toUpperCase(), bean);
				}
			}
		}

		for(ProjectIdentification bean : ossComponentList) {
			if(ossNickNameConvertMap.containsKey(avoidNull(bean.getOssName()).trim().toUpperCase())) {
				bean.setOssName(ossNickNameConvertMap.get(avoidNull(bean.getOssName()).trim().toUpperCase()).getOssName());
			}
			
			// license nickname 체크
			if(CoConstDef.LICENSE_DIV_SINGLE.equals(bean.getLicenseDiv())) {
				String _licenseName = avoidNull(bean.getLicenseName()).trim();
				
				if(CoCodeManager.LICENSE_INFO_UPPER.containsKey(_licenseName.toUpperCase())) {
					LicenseMaster licenseMaster = CoCodeManager.LICENSE_INFO_UPPER.get(_licenseName.toUpperCase());
					
					if(licenseMaster.getLicenseNicknameList() != null && !licenseMaster.getLicenseNicknameList().isEmpty()) {
						for(String s : licenseMaster.getLicenseNicknameList()) {
							if(_licenseName.equalsIgnoreCase(s)) {
								bean.setLicenseName(avoidNull(licenseMaster.getShortIdentifier(), licenseMaster.getLicenseNameTemp()));
							
								break;
							}
						}
					}
				}
			}
		}
		
		return ossComponentList;
	}
	
	private List<List<ProjectIdentification>> convertLicenseNickName(
			List<List<ProjectIdentification>> ossComponentLicenseList) {
		if(ossComponentLicenseList != null) {
			for(List<ProjectIdentification> licenseList : ossComponentLicenseList) {
				for (ProjectIdentification licenseBean : licenseList) {
					String _licenseName = avoidNull(licenseBean.getLicenseName()).trim();
					
					if(CoCodeManager.LICENSE_INFO_UPPER.containsKey(_licenseName.toUpperCase())) {
						LicenseMaster licenseMaster = CoCodeManager.LICENSE_INFO_UPPER.get(_licenseName.toUpperCase());
						
						if(licenseMaster.getLicenseNicknameList() != null && !licenseMaster.getLicenseNicknameList().isEmpty()) {
							for(String s : licenseMaster.getLicenseNicknameList()) {
								if(_licenseName.equalsIgnoreCase(s)) {
									licenseBean.setLicenseName(avoidNull(licenseMaster.getShortIdentifier(), licenseMaster.getLicenseNameTemp()));
									
									break;
								}
							}
						}
					}
				}
			}
		}
		
		return ossComponentLicenseList;
	}
	
	@Override
	public List<Vulnerability> getAllVulnListWithProject(String prjId) {
		// 성능 이슈로 nickname 조건을 분리하여 2번 쿼리후 java 에서 merge 하는 방식으로 변경
		List<Vulnerability> list = new ArrayList<>();
		Map<String, Vulnerability> duplCheck = new HashMap<>();
		Project param = new Project();
		param.setPrjId(prjId);
		
		List<String> vulnList = selfCheckMapper.getAllVulnList(param);
		List<Vulnerability> list1 = selfCheckMapper.getAllVulnListWithProject(param);
			
		if(list1 != null) {
			for(Vulnerability bean : list1) {
				if(vulnList.contains(bean.getProduct())) {
					String key = avoidNull(avoidNull(bean.getVendor()) + "_" + avoidNull(bean.getProduct()) + "_" + avoidNull(bean.getVersion()) + "_" + bean.getCveId());
					
					if(!duplCheck.containsKey(key)) {
						duplCheck.put(key, bean);
					}
				}
			}
		}

		List<Vulnerability> list2 = selfCheckMapper.getAllVulnListWithProjectByNickName(param);
		
		if(list2 != null) {
			for(Vulnerability bean : list2) {
				String key = avoidNull(avoidNull(bean.getVendor()) + "_" + avoidNull(bean.getProduct()) + "_" + avoidNull(bean.getVersion()) + "_" + bean.getCveId());
				if(!duplCheck.containsKey(key)) {
					duplCheck.put(key, bean);
				}
			}
		}
		
		// sort by key
		Map<String, Vulnerability> sortMap = new TreeMap<>(duplCheck);

		list.addAll(sortMap.values());
		
		return list;
	}
	
	@Override
	public void addWatcher(Project project) {
		if(!isEmpty(project.getPrjEmail())) {
			// 이미 추가된 watcher 체크
			if(selfCheckMapper.existsWatcherByEmail(project) == 0) {
				// watcher 추가
				selfCheckMapper.insertWatcher(project);
				
				// email 발송
				try {
					CoMail mailBean = new CoMail(CoConstDef.CD_MAIL_TYPE_SELFCHECK_PROJECT_WATCHER_INVATED);
					mailBean.setParamPrjId(project.getPrjId());
					mailBean.setParamUserId(project.getLoginUserName());
					mailBean.setParamEmail(project.getPrjEmail());
					
					CoMailManager.getInstance().sendMail(mailBean);
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				}
			}
		} else {
			// 이미 추가된 watcher 체크
			if(selfCheckMapper.existsWatcherByUser(project) == 0) {
				// watcher 추가
				selfCheckMapper.insertWatcher(project);
			}
		}
	}
	
	@Override
	public void removeWatcher(Project project) {
		selfCheckMapper.removeWatcher(project);		
	}
	
	@Override
	public List<Project> copyWatcher(Project project) {
		return selfCheckMapper.copyWatcher(project);
	}
	
	@Override
	public boolean existsWatcher(Project project) {
		boolean result = false;
		
		int i = selfCheckMapper.existsWatcher(project);
		
		if(i > 0){
			result = true;
		}	
		
		return result;
	}
	
	@Override
	public String getUserList() {
		return null;
	}

	@Override
	public String getCategoryCode(String code, String gubun) {
		return null;
	}

	@Override
	public List<ProjectIdentification> getOssNames(ProjectIdentification identification) {
		return null;
	}

	@Override
	public List<ProjectIdentification> getOssVersions(String ossName) {
		return null;
	}

	@Override
	public Map<String, Object> getOssIdLicenses(ProjectIdentification identification) {
		return null;
	}

	@Override
	public String getDivision(Project project) {
		return null;
	}

	@Override
	public List<Project> getProjectNameList(Project project) {
		return null;
	}

	@Override
	public List<Map<String, Object>> getCategoryCodeToJson(String code) {
		return null;
	}

	@Override
	public boolean existProjectData(Project project) {
		return false;
	}

	@Override
	public Map<String, List<String>> nickNameValid(List<ProjectIdentification> ossComponent,
			List<List<ProjectIdentification>> ossComponentLicense) {
		return null;
	}

	@Override
	public List<Project> getProjectListExcel(Project project) {
		return null;
	}

	@Override
	public Map<String, Object> identificationSubGrid(ProjectIdentification identification) {
		return null;
	}

	@Override
	public Map<String, Object> getPartnerList(PartnerMaster partnerMaster) {
		return null;
	}

	@Override
	public Map<String, Object> getIdentificationProject(Project project) {
		return null;
	}

	@Override
	public Map<String, Object> getPartnerOssList(OssComponents ossComponents) {
		return null;
	}

	@Override
	public Map<String, Object> getIdentificationProjectSearch(ProjectIdentification projectIdentification) {
		return null;
	}

	@Override
	public String getReviewerList() {
		return null;
	}

	@Override
	public Map<String, Object> getIdentificationThird(OssComponents ossComponents) {
		return null;
	}

	@Override
	public List<Project> getProjectCreator() {
		return null;
	}

	@Override
	public List<Project> getProjectReviwer() {
		return null;
	}
	
	@Override
	public Project selectProjectDetailExcel(String parameter) {
		return null;
	}

	@Override
	public List<ProjectIdentification> getProjectReportExcelList(ProjectIdentification identification) {
		return null;
	}

	@Override
	public List<Project> getProjectVersionList(Project project) {
		return null;
	}

	@Override
	public void registPackageFile(List<UploadFile> list, String prjId) {
		
	}

	@Override
	public HashMap<String, Object> applySrcAndroidModel(Project project) {
		return null;
	}

	@Override
	public void updateSubStatus(Project project) {
		
	}

	@Override
	public List<UploadFile> selectAndroidFileDetail(Project project) {
		return null;
	}

	@Override
	public LicenseMaster getLicenseMaster(LicenseMaster license) {
		return null;
	}

	@Override
	public Map<String, Object> getOssIdCheck(ProjectIdentification projectIdentification) {
		return null;
	}

	@Override
	public Map<String, Object> applySrcAndroidModel(List<ProjectIdentification> list, List<String> noticeBinaryList)
			throws IOException {
		return null;
	}

	@Override
	public Map<String, Map<String, String>> getProjectDownloadExpandInfo(Project param) {
		return null;
	}

	@Override
	public boolean isPermissiveOnlyAndGeneralNotice(String prjId, boolean isAndroidModel) {
		return false;
	}

	@Override
	public void cancelFileDel(Project project) {}

	@Override
	public List<OssComponents> selectOssComponentsListByComponentIds(OssComponents param) {
		return null;
	}

	@Override
	public Map<String, Object> registBatWithFileUploadByProject(MultipartHttpServletRequest req, T2File file,
			BinaryMaster binary) {
		return null;
	}

	@Override
	public void updateProjectMaster(Project project) {}

	@Override
	public Map<String, Object> getFileInfo(ProjectIdentification identification) {
		return null;
	}

	@Override
	public Map<String, Object> registBatWithFileUploadByProjectByUrl(HttpServletRequest request, T2File file,
			BinaryMaster binary, Map<Object, Object> map) {
		return null;
	}

	@Override
	public Map<String, Object> get3rdMapList(Project project) {
		return null;
	}

	@Override
	public Map<String, Object> getThirdPartyMap(String prjId) {
		return null;
	}

	@Override
	public void updateWithoutVerifyYn(OssNotice ossNotice) {}

	@Override
	public OssNotice setCheckNotice(Project project) {
		OssNotice notice = new OssNotice();
		
		try {
			String prjId = project.getPrjId();
			notice = this.selectOssNoticeOne(prjId);
			
			if(isEmpty(notice.getCompanyNameFull()) 
					&& isEmpty(notice.getDistributionSiteUrl()) 
					&& isEmpty(notice.getEmail())
					&& isEmpty(notice.getAppended())
					&& CoConstDef.FLAG_NO.equals(notice.getEditNoticeYn())
					&& CoConstDef.FLAG_NO.equals(notice.getEditCompanyYn())
					&& CoConstDef.FLAG_NO.equals(notice.getEditDistributionSiteUrlYn())
					&& CoConstDef.FLAG_NO.equals(notice.getEditEmailYn())
					&& CoConstDef.FLAG_NO.equals(notice.getHideOssVersionYn())
					&& CoConstDef.FLAG_NO.equals(notice.getEditAppendedYn())
					&& (CoConstDef.CD_NOTICE_TYPE_GENERAL.equals(notice.getNoticeType()) 
							|| CoConstDef.CD_NOTICE_TYPE_NA.equals(notice.getNoticeType()))){
				// OSS_NOTICE와 OSS_NOTICE_NEW에 정보가 없을경우 default setting
				notice.setEditNoticeYn(CoConstDef.FLAG_NO);
				notice.setEditCompanyYn(CoConstDef.FLAG_YES);
				notice.setEditDistributionSiteUrlYn(CoConstDef.FLAG_YES);
				notice.setEditEmailYn(CoConstDef.FLAG_YES);
				notice.setHideOssVersionYn(CoConstDef.FLAG_NO);
				notice.setEditAppendedYn(CoConstDef.FLAG_NO);
				notice.setPrjId(project.getPrjId());
				
				String distributeType = avoidNull(project.getDistributeTarget(), CoConstDef.CD_DISTRIBUTE_SITE_SKS); // LGE, NA => LGE로 표기, SKS => SKS로 표기함.
				String distributeCode = CoConstDef.CD_DISTRIBUTE_SITE_SKS.equals(distributeType) ? CoConstDef.CD_NOTICE_DEFAULT_SKS : CoConstDef.CD_NOTICE_DEFAULT;
				
				if(isEmpty(notice.getCompanyNameFull())) {
					notice.setCompanyNameFull(CoCodeManager.getCodeExpString(distributeCode, CoConstDef.CD_DTL_NOTICE_DEFAULT_FULLNAME));
				}
				
				if(isEmpty(notice.getDistributionSiteUrl())) {
					notice.setDistributionSiteUrl(CoCodeManager.getCodeExpString(distributeCode, CoConstDef.CD_DTL_NOTICE_DEFAULT_DISTRIBUTE_SITE));
				}
				
				if(isEmpty(notice.getEmail())) {
					notice.setEmail(CoCodeManager.getCodeExpString(distributeCode, CoConstDef.CD_DTL_NOTICE_DEFAULT_EMAIL));
				}
				
				if(isEmpty(notice.getAppended())){
					notice.setAppended(CoCodeManager.getCodeExpString(distributeCode, CoConstDef.CD_DTL_NOTICE_DEFAULT_APPENDED));
				}
			} else if(CoConstDef.FLAG_YES.equals(notice.getEditNoticeYn())
					&& CoConstDef.CD_NOTICE_TYPE_GENERAL.equals(notice.getNoticeType())) {
				
			} else {
				if(!isEmpty(notice.getCompanyNameFull())){
					notice.setEditCompanyYn(CoConstDef.FLAG_YES);
					notice.setEditNoticeYn(CoConstDef.FLAG_YES);
				}
				
				if(!isEmpty(notice.getDistributionSiteUrl())){
					notice.setEditDistributionSiteUrlYn(CoConstDef.FLAG_YES);
					notice.setEditNoticeYn(CoConstDef.FLAG_YES);
				}
				
				if(!isEmpty(notice.getEmail())){
					notice.setEditEmailYn(CoConstDef.FLAG_YES);
					notice.setEditNoticeYn(CoConstDef.FLAG_YES);
				}
				
				if(!isEmpty(notice.getAppended())){
					notice.setEditAppendedYn(CoConstDef.FLAG_YES);
					notice.setEditNoticeYn(CoConstDef.FLAG_YES);
				}
				
				if(CoConstDef.CD_NOTICE_TYPE_GENERAL_WITHOUT_OSS_VERSION.equals(project.getNoticeType())){
					notice.setHideOssVersionYn(CoConstDef.FLAG_YES);
					notice.setEditNoticeYn(CoConstDef.FLAG_YES);
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		
		return notice;
	}

	@Override
	public OssNotice selectOssNoticeOne(String prjId) {
		Project project = new Project();
		project.setPrjId(prjId);
		
		return selfCheckMapper.selectOssNoticeOne(project);
	}

	@Override
	public List<OssComponents> getVerifyOssList(Project projectMaster) {
		List<OssComponents> componentList = selfCheckMapper.selectVerifyOssList(projectMaster);
		if(componentList != null && !componentList.isEmpty() && componentList.get(0) == null) {
			componentList = new ArrayList<>();
		}
		
		return componentList;
	}

	@Override
	public Map<String, Object> getVerificationOne(Project project) {
		
		HashMap<String, Object> map = new HashMap<String, Object>();
		Project prj = selfCheckMapper.selectProjectMaster(project);

		String comment = prj != null ? prj.getComment() : null;
		String content = commentMapper.getContent(comment);
		OssNotice ossNotice = selfCheckMapper.selectOssNoticeOne(project);

		map.put("data", prj);
		map.put("commentText", content);
		map.put("notice", ossNotice);

		return map;
	}

	@Override
	public Project getProjectBasicInfo(String prjId) {
		Project param = new Project();
		param.setPrjId(prjId);
		
		return selfCheckMapper.selectProjectMaster2(param);
	}

	@Override
	public boolean getNoticeHtmlFile(OssNotice ossNotice) throws IOException {
		return getNoticeHtmlFile(ossNotice, null);
	}
	
	@Override
	public boolean getNoticeHtmlFile(OssNotice ossNotice, String contents) throws IOException {
		Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		
		// OSS Notice가 N/A이면 고지문을 생성하지 않는다.
		if(CoConstDef.CD_NOTICE_TYPE_NA.equals(prjInfo.getNoticeType())) {
			return true;
		}
		
		prjInfo.setUseCustomNoticeYn(!isEmpty(contents) ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
		contents = avoidNull(contents, getNoticeHtml(ossNotice));
		
		if("binAndroid".equals(contents)) {
			return getAndroidNoticeVelocityTemplateFile(prjInfo); // file Content 옮기는 기능에서 files.copy로 변경
		} else {
			return getNoticeVelocityTemplateFile(contents, prjInfo);	
		}
	}

	@Override
	public String getNoticeHtml(OssNotice ossNotice) throws IOException {
		Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		String androidNoticeContents = getAndroidNotice(prjInfo);
		
		if(CoConstDef.FLAG_YES.equals(ossNotice.getPreviewOnly()) && !isEmpty(androidNoticeContents)) {
			return androidNoticeContents;
		} else {
			if(!isEmpty(androidNoticeContents)) {
				return "binAndroid"; 
			} else {
				ossNotice.setNetworkServerFlag(prjInfo.getNetworkServerType());

				// Convert Map to Apache Velocity Template
				return CommonFunction.VelocityTemplateToString(getNoticeHtmlInfo(ossNotice));
			}
		}
	}

	private String getAndroidNotice(Project prjInfo) throws IOException {
		// distribution type이 Android 이면서
		// Android Build Image이외의 OSS List가 포함된 경우는 병합
		// android build image만 사용된 경우는 notice.html을 반환한다.
		// 이슈로 인해 android project 기준이 변경이 되었으며 NoticeType이 20인 경우는 전부 Android Project형태를 띄고 있도록 변경이 되었음.
		Map<String, Object> NoticeInfo = selfCheckMapper.getNoticeTypeReturnMap(prjInfo.getPrjId());
		
		if(prjInfo != null 
				&& CoConstDef.CD_NOTICE_TYPE_PLATFORM_GENERATED.equals(avoidNull((String) NoticeInfo.get("noticeType"), CoConstDef.CD_DTL_NOTICE_TYPE_GENERAL)) 
				&& !isEmpty(prjInfo.getSrcAndroidNoticeFileId())) {
			T2File androidFile = fileService.selectFileInfoById(prjInfo.getSrcAndroidNoticeFileId());
			
			return CommonFunction.getStringFromFile(androidFile.getLogiPath() + "/" + androidFile.getLogiNm());
		}
		
		return null;
	}

	private boolean getAndroidNoticeVelocityTemplateFile(Project project) {
		boolean procResult = true;
		try {
			// file path and name 설정
			// 파일 path : <upload_home>/notice/
			// 파일명 : 임시: 프로젝트ID_yyyyMMdd\
			String filePath = NOTICE_PATH + "/" + project.getPrjId();
			T2File baseFile = null;
			String basePath = null;
			
			if(isEmpty(project.getSrcAndroidNoticeXmlId()) && !isEmpty(project.getSrcAndroidNoticeFileId())) {
				baseFile = fileMapper.selectFileInfoById(project.getSrcAndroidNoticeFileId());
				basePath = CommonFunction.emptyCheckProperty("upload.path", "/upload") + "/" + baseFile.getLogiNm();
			} else {
				baseFile = fileMapper.selectFileInfoById(project.getSrcAndroidNoticeXmlId());
				basePath = baseFile.getLogiPath() + "/" + baseFile.getLogiNm();
			}
			
			// 이전에 생성된 파일은 모두 삭제한다.
			Path rootPath = Paths.get(filePath);
			
			if(rootPath.toFile().exists()) {
				for(String _fName : rootPath.toFile().list()) {
					Files.deleteIfExists(rootPath.resolve(_fName));
					
					T2File file = new T2File();
					file.setLogiNm(_fName);
					file.setLogiPath(filePath);
					
					int returnSuccess = fileMapper.updateFileDelYnByFilePathNm(file);
					
					if(returnSuccess > 0){
						log.debug(filePath + "/" + _fName + " is delete success.");
					}else{
						log.debug(filePath + "/" + _fName + " is delete failed.");
					}
				}
			}
			
			String fileName = CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), CommonFunction.getCurrentDateTime("yyMMdd"), "html");
			
			if(oss.fosslight.util.FileUtil.copyFile(basePath, filePath, fileName)) {
				// 파일 등록
				String FileSeq = fileService.registFileWithFileName(filePath, fileName);
				
				// project 정보 업데이트
				Project projectParam = new Project();
				projectParam.setPrjId(project.getPrjId());
				projectParam.setNoticeFileId(FileSeq);
				projectParam.setUseCustomNoticeYn(StringUtil.nvl(project.getUseCustomNoticeYn(),CoConstDef.FLAG_NO));
				
				selfCheckMapper.updateNoticeFileInfo(projectParam);
			} else {
				procResult = false;
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			procResult = false;
		}
		
		return procResult;
	}

	private boolean getNoticeVelocityTemplateFile(String contents, Project project) {
		boolean procResult = true;
		
		try {
			// file path and name 설정
			// 파일 path : <upload_home>/notice/
			// 파일명 : 임시: 프로젝트ID_yyyyMMdd\
			
			String filePath = NOTICE_PATH + "/" + project.getPrjId();
			// 이전에 생성된 파일은 모두 삭제한다.
			Path rootPath = Paths.get(filePath);
			if(rootPath.toFile().exists()) {
				for(String _fName : rootPath.toFile().list()) {
					Files.deleteIfExists(rootPath.resolve(_fName));
					
					T2File file = new T2File();
					file.setLogiNm(_fName);
					file.setLogiPath(filePath);
					
					int returnSuccess = fileMapper.updateFileDelYnByFilePathNm(file);
					
					if(returnSuccess > 0){
						log.debug(filePath + "/" + _fName + " is delete success.");
					}else{
						log.debug(filePath + "/" + _fName + " is delete failed.");
					}
				}
			}			
			
			String fileName = CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), CommonFunction.getCurrentDateTime("yyMMdd"), "html");
			
			if(oss.fosslight.util.FileUtil.writhFile(filePath, fileName, contents)) {
				// 파일 등록
				String FileSeq = fileService.registFileWithFileName(filePath, fileName);
				
				// project 정보 업데이트
				Project projectParam = new Project();
				projectParam.setPrjId(project.getPrjId());
				projectParam.setNoticeFileId(FileSeq);
				projectParam.setUseCustomNoticeYn(StringUtil.nvl(project.getUseCustomNoticeYn(),CoConstDef.FLAG_NO));
				
				selfCheckMapper.updateNoticeFileInfo(projectParam);
			} else {
				procResult = false;
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			
			procResult = false;
		}
		
		return procResult;
	}

	@Override
	@Transactional
	public void updateStatusWithConfirm(Project project, OssNotice ossNotice) throws Exception {
		updateProjectStatus(project);
		
		boolean makeZipFile = false;
		String spdxComment = "";
		
		// html simple
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSimpleHTMLYn())) {
			ossNotice.setSimpleNoticeFlag(CoConstDef.FLAG_YES);
			ossNotice.setFileType("html");
			project.setSimpleHtmlFileId(getNoticeTextFileForPreview(ossNotice, true));
			makeZipFile = true;
		}

		// text
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadNoticeTextYn())) {
			ossNotice.setSimpleNoticeFlag(CoConstDef.FLAG_NO);
			ossNotice.setFileType("text");
			project.setNoticeTextFileId(getNoticeTextFileForPreview(ossNotice, true));
			makeZipFile = true;
		}
		
		// text simple
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSimpleTextYn())) {
			ossNotice.setSimpleNoticeFlag(CoConstDef.FLAG_YES);
			ossNotice.setFileType("text");
			project.setSimpleTextFileId(getNoticeTextFileForPreview(ossNotice, true));
			makeZipFile = true;
		}
		
		// SPDX
		String spdxSheetFileId = null;
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXSheetYn())) {
			spdxSheetFileId = ExcelDownLoadUtil.getExcelDownloadId("spdx", project.getPrjId(), EXPORT_TEMPLATE_PATH);
			if(!isEmpty(spdxSheetFileId)) {
				T2File spdxFileInfo = fileService.selectFileInfo(spdxSheetFileId);
				Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
				String fileName = "spdx_" + CommonFunction.getNoticeFileName(prjInfo.getPrjId(), prjInfo.getPrjName(), prjInfo.getPrjVersion(), CommonFunction.getCurrentDateTime("yyMMdd"), "");
				fileName += "."+FilenameUtils.getExtension(spdxFileInfo.getOrigNm());
				String filePath = NOTICE_PATH + "/" + prjInfo.getPrjId();
				FileUtil.moveTo(spdxFileInfo.getLogiPath() + "/" + spdxFileInfo.getLogiNm(), filePath, fileName);
				project.setSpdxSheetFileId(fileService.registFileDownload(filePath, fileName, fileName));
				spdxSheetFileId = ossNotice.getSpdxSheetFileId();
				makeZipFile = true;
			}

		}
		
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXRdfYn())) {
			if(isEmpty(spdxSheetFileId)) {
				spdxSheetFileId = ExcelDownLoadUtil.getExcelDownloadId("spdx", project.getPrjId(), EXPORT_TEMPLATE_PATH);
			}
			
			if(!isEmpty(spdxSheetFileId)) {
				T2File spdxFileInfo = fileService.selectFileInfo(spdxSheetFileId);
				String sheetFullPath = spdxFileInfo.getLogiPath();
				
				if(!sheetFullPath.endsWith("/")) {
					sheetFullPath += "/";
				}
				
				sheetFullPath += spdxFileInfo.getLogiNm();
				String targetFileName = FilenameUtils.getBaseName(spdxFileInfo.getLogiNm())+".rdf";
				String resultFileName = FilenameUtils.getBaseName(spdxFileInfo.getOrigNm())+".rdf";
				String tagFullPath = spdxFileInfo.getLogiPath();
				
				if(!tagFullPath.endsWith("/")) {
					tagFullPath += "/";
				}
				
				tagFullPath += targetFileName;
				SPDXUtil2.convert(project.getPrjId(), sheetFullPath, tagFullPath);
				File spdxRdfFile = new File(tagFullPath);
				
				if(spdxRdfFile.exists() && spdxRdfFile.length() <= 0) {
					if(!isEmpty(spdxComment)) {
						spdxComment += "<br>";
					}
					
					spdxComment += getMessage("spdx.rdf.failure"); 
				}
				
				String filePath = NOTICE_PATH + "/" + project.getPrjId();
				FileUtil.moveTo(tagFullPath, filePath, resultFileName);
				project.setSpdxRdfFileId(fileService.registFileDownload(filePath, resultFileName, resultFileName));
				
				makeZipFile = true;
			}
		}
		
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXTagYn())) {
			if(isEmpty(spdxSheetFileId)) {
				spdxSheetFileId = ExcelDownLoadUtil.getExcelDownloadId("spdx", project.getPrjId(), EXPORT_TEMPLATE_PATH);
			}
			
			if(!isEmpty(spdxSheetFileId)) {
				T2File spdxFileInfo = fileService.selectFileInfo(spdxSheetFileId);
				
				String sheetFullPath = spdxFileInfo.getLogiPath();
				
				if(!sheetFullPath.endsWith("/")) {
					sheetFullPath += "/";
				}
				
				sheetFullPath += spdxFileInfo.getLogiNm();
				String targetFileName = FilenameUtils.getBaseName(spdxFileInfo.getLogiNm())+".tag";
				String resultFileName = FilenameUtils.getBaseName(spdxFileInfo.getOrigNm())+".tag";
				String tagFullPath = spdxFileInfo.getLogiPath();
				
				if(!tagFullPath.endsWith("/")) {
					tagFullPath += "/";
				}
				
				tagFullPath += targetFileName;
				SPDXUtil2.convert(project.getPrjId(), sheetFullPath, tagFullPath);
				
				File spdxTafFile = new File(tagFullPath);
				
				if(spdxTafFile.exists() && spdxTafFile.length() <= 0) {
					if(!isEmpty(spdxComment)) {
						spdxComment += "<br>";
					}
					
					spdxComment += getMessage("spdx.tag.failure"); 
				}
				
				String filePath = NOTICE_PATH + "/" + project.getPrjId();
				FileUtil.moveTo(tagFullPath, filePath, resultFileName);
				project.setSpdxTagFileId(fileService.registFileDownload(filePath, resultFileName, resultFileName));
				
				makeZipFile = true;
			}
		}
		
		// zip파일 생성
		if(makeZipFile) {
			String noticeRootDir = NOTICE_PATH;
			ossNotice.setFileType(".zip");
			Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
			String zipFileName = CommonFunction.getNoticeFileName(prjInfo.getPrjId(), prjInfo.getPrjName(), prjInfo.getPrjVersion(), CommonFunction.getCurrentDateTime("yyMMdd"), ".zip");
			FileUtil.zip(noticeRootDir + "/" + project.getPrjId(), noticeRootDir, zipFileName, "OSS Notice");
			
			String zipFileId = fileService.registFileDownload(noticeRootDir, zipFileName, zipFileName);
			project.setZipFileId(zipFileId);
		}
		
		selfCheckMapper.updateNoticeFileInfoEtc(project); // file info update
		
		if(!isEmpty(spdxComment)) { // spdx failure => comment regist
			try {
				CommentsHistory commHisBean = new CommentsHistory();
				commHisBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_PACKAGING_HIS);
				commHisBean.setReferenceId(project.getPrjId());
				commHisBean.setContents(spdxComment);
				commHisBean.setStatus(CoCodeManager.getCodeExpString(CoConstDef.CD_IDENTIFICATION_STATUS, CoConstDef.CD_DTL_IDENTIFICATION_STATUS_CONFIRM));
				
				commentService.registComment(commHisBean);
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
		}
	}

	@Override
	@Transactional
	@CacheEvict(value="autocompleteProjectCache", allEntries=true)
	public void updateProjectStatus(Project project) {
		CoMail mailBean = null;
		//review 상태로 변경시 reviewer가 설정되어 있지 않은 경우, reviewer도 업데이트 한다.
		if(CoConstDef.CD_DTL_IDENTIFICATION_STATUS_REVIEW.equals(project.getIdentificationStatus())) {
			Project param = new Project();
			param.setPrjId(project.getPrjId());
			param = selfCheckMapper.selectProjectMaster2(param);
			
			if(isEmpty(param.getReviewer())) {
				param.setModifier(param.getLoginUserName());
				param.setReviewer(param.getLoginUserName());
				
				selfCheckMapper.updateReviewer(param);
				
				mailBean = new CoMail(CoConstDef.CD_MAIL_TYPE_PROJECT_REVIEWER_ADD);
				mailBean.setToIds(new String[] {param.getLoginUserName()});
				mailBean.setParamPrjId(project.getPrjId());
			}
		}
		
		// 프로젝트 완료처리
		if(CoConstDef.FLAG_YES.equals(project.getCompleteYn())) {
			// Identification N/A 처리
			// identification sub status
			// distribution status
			Project _param = new Project();
			_param.setPrjId(project.getPrjId());
			
			selfCheckMapper.updateProjectStatusWithComplete(_param);
		} else {
			//다운로드 허용 플래그
			project.setAllowDownloadBitFlag(allowDownloadMultiFlagToBitFlag(project));
			
			// 프로젝트 상태 변경
			selfCheckMapper.updateProjectMaster(project);
		}
		
		if(mailBean != null) {
			try {
				CoMailManager.getInstance().sendMail(mailBean);
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public History work(Object param) {
		History h = new History();
		
		if(Project.class.equals(param.getClass())) {
			Project vo = (Project) param;
			Project prj = getProjectDetail(vo);
			h.sethKey(prj.getPrjId());
			h.sethTitle(prj.getPrjName());
			h.sethType(CoConstDef.EVENT_CODE_PROJECT);
			h.setModifier(prj.getLoginUserName());
			h.setModifiedDate(prj.getModifiedDate());
			h.sethComment("");
			h.sethData(prj);
		} else {
			List<ProjectIdentification> vo = (List<ProjectIdentification>) param;
			// List<ProjectIdentification> data = getBomListExcel(vo.get(0));
			Project prj = new Project();
			// prj.setPrjId(data.get(0).getReferenceId());
			prj = getProjectDetail(prj);
			
			h.sethKey(prj.getPrjId());
			h.sethTitle(prj.getPrjName());
			h.sethType(CoConstDef.EVENT_CODE_BOM);
			h.setModifier(prj.getLoginUserName());
			h.setModifiedDate(prj.getModifiedDate());
			h.sethComment("");
			// h.sethData(data);
		}
		
		return h;
	}

	@Override
	public void changePackageFileNameDistributeFormat(String prjId) {
		// 프로젝트 기본정보 취득
		Project prjBean = new Project();
		prjBean.setPrjId(prjId);
		prjBean = selfCheckMapper.selectProjectMaster2(prjBean);
		List<String> packageFileIds = new ArrayList<String>();
		
		if(!isEmpty(prjBean.getPackageFileId())) {
			packageFileIds.add(prjBean.getPackageFileId());
		}
		
		if(!isEmpty(prjBean.getPackageFileId2())) {
			packageFileIds.add(prjBean.getPackageFileId2());
		}
		
		if(!isEmpty(prjBean.getPackageFileId3())) {
			packageFileIds.add(prjBean.getPackageFileId3());
		}
		
		int fileSeq = 1;
		
		for(String packageFileId : packageFileIds){
			T2File packageFileInfo = new T2File();
			packageFileInfo.setFileSeq(packageFileId);
			packageFileInfo = fileMapper.getFileInfo(packageFileInfo);
			
			if(packageFileInfo != null) {
				String orgFileName = packageFileInfo.getOrigNm();
				// Packaging > Confirm시 Packaging 파일명 변경 건
				String paramSeq = (packageFileIds.size() > 1 ? Integer.toString(fileSeq++) : ""); 
				String chgFileName = getPackageFileName(prjBean.getPrjName(), prjBean.getPrjVersion(), packageFileInfo.getOrigNm(), paramSeq);
				
				packageFileInfo.setOrigNm(chgFileName);
				
				fileMapper.upateOrgFileName(packageFileInfo);
				
				// 이력을 남긴다.
				CommentsHistory commHisBean = new CommentsHistory();
				commHisBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_PACKAGING_HIS);
				commHisBean.setReferenceId(prjId);
				commHisBean.setContents("Changed File Name (\""+orgFileName+"\") to \""+chgFileName+"\"  ");
				
				commentService.registComment(commHisBean);
			}
		}
	}

	// @Override
	// public String getNoticeTextFileForPreview(OssNotice ossNotice, boolean isConfirm) throws IOException {
	// 	Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		
	// 	return getNoticeVelocityTemplateFileForPreview(getNoticeHtml(ossNotice), prjInfo, ossNotice.getFileType(), ossNotice.getSimpleNoticeFlag(), isConfirm);	
	// }

	// private String getNoticeVelocityTemplateFileForPreview(String contents, Project project, String fileType, String simpleFlag, boolean isConfirm) throws IOException {
	// 	String fileId = "";
	// 	String filePath = "";
	// 	String fileName = "";
	// 	// Text 형식 OSS 고지문 생성 시 개행문자 변경
	// 	// System.getProperty("line.separator") => "\n" => "\r\n" 변경 
	// 	String line = "\r\n";
		
	// 	if(fileType == "text"){
	// 		fileId = "";
	// 		filePath = NOTICE_PATH + ( isConfirm ? "/" : "/preview/") + project.getPrjId();
	// 		fileName = (CoConstDef.FLAG_YES.equals(simpleFlag) ? "simple_" : "") + CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), ( isConfirm ? CommonFunction.getCurrentDateTime("yyMMdd") : DateUtil.getCurrentDateTime(DateUtil.DATE_HMS_PATTERN) ), fileType);
	// 		contents = contents.replace("\n", line).replace(",)", ")").replace("<br>", line).replace("&copy;", "©").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "\'");
	// 	} else {
	// 		fileId = "";
	// 		filePath = NOTICE_PATH + ( isConfirm ? "/" : "/preview/") + project.getPrjId();
	// 		fileName = (CoConstDef.FLAG_YES.equals(simpleFlag) ? "simple_" : "") + CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), ( isConfirm ? CommonFunction.getCurrentDateTime("yyMMdd") : DateUtil.getCurrentDateTime(DateUtil.DATE_HMS_PATTERN) ), fileType);
			
	// 		// custom edit를 사용하고, packaging confirm 인 경우 이면서 simple인 경우
	// 		// license text 부분만 다시 변경한다.
	// 		if(isConfirm && CoConstDef.FLAG_YES.equals(simpleFlag) && CoConstDef.FLAG_YES.equals(project.getUseCustomNoticeYn())) {
	// 			// 이미 생성된 고지문구 파일의 내용을 가져온다.
	// 			T2File defaultNoticeFileInfo = fileService.selectFileInfo(project.getNoticeFileId());
				
	// 			if(defaultNoticeFileInfo != null) {
	// 				File noticeFile = new File(defaultNoticeFileInfo.getLogiPath() + "/" + defaultNoticeFileInfo.getLogiNm());
	// 				if(noticeFile.exists()) {
	// 					Document doc = Jsoup.parse(noticeFile, "UTF8");
	// 					Document doc2 = Jsoup.parse(contents);
						
	// 					doc.select("body > p.bdTop.license").remove();
	// 					doc.select("body").append(doc2.select("body > p.bdTop.license").toString());
	// 					doc.select("body").append(doc2.select("body > ul.bdTop2.license").toString());
						
	// 					contents = doc.toString();
	// 				}
	// 			}
	// 		}
	// 	}

	// 	if(FileUtil.writhFile(filePath, fileName, contents)) {
	// 		// 파일 등록
	// 		fileId = fileService.registFileDownload(filePath, fileName, fileName);
	// 	}
		
	// 	return fileId;
	// }

	public Map<String, Object> getNoticeHtmlInfo(OssNotice ossNotice) {
		Map<String, Object> model = new HashMap<String, Object>();
		
		String noticeType = "";
		String prjName = "";
		String prjVersion = "";
		String prjId = "";
		String distributeSite = "";
		int dashSeq = 0;
		String hideOssVersionYn = ossNotice.getHideOssVersionYn();
		
		// NETWORK SERVER 여부를 체크한다.
		
		Project project = new Project();
		project.setPrjId(ossNotice.getPrjId());
		project = selfCheckMapper.getProjectBasicInfo(project);
		if(project != null){
			if(isEmpty(prjName)) {
				prjName = project.getPrjName();
			}
			
			if(isEmpty(prjId)) {
				prjId = project.getPrjId();
			}
			
			if(isEmpty(prjVersion)) {
				prjVersion = project.getPrjVersion();
			}
			
			if(isEmpty(distributeSite)) {
				distributeSite = project.getDistributeTarget();
			}
		}
		List<OssComponents> ossComponentList = selfCheckMapper.selectVerificationNotice(ossNotice);
		// TYPE별 구분
		Map<String, OssComponents> noticeInfo = new LinkedHashMap<>();
		Map<String, OssComponents> srcInfo = new LinkedHashMap<>();
		Map<String, OssComponentsLicense> licenseInfo = new LinkedHashMap<>();
		
		OssComponents ossComponent;
		
		for(OssComponents bean : ossComponentList) {
			String componentKey = (CoConstDef.FLAG_YES.equals(hideOssVersionYn) 
									? bean.getOssName() 
									: bean.getOssName() + "|" + bean.getOssVersion()).toUpperCase();
			
			if("-".equals(bean.getOssName())) {
				componentKey += dashSeq++;
			}
			
			// type
			boolean isDisclosure = CoConstDef.CD_DTL_OBLIGATION_DISCLOSURE.equals(bean.getObligationType());
			// 2017.05.16 add by yuns start
			// obligation을 특정할 수 없는 oss도 bom에 merge 되도록 수정하면서, identification confirm시 refDiv가 '50'(고지대상)에 obligation을 특정할 수 없는 oss도 포함되어 등록되어
			// confirm 처리에서 obligation이 고지의무가 있거나 소스코드 공개의무가 있는 경우만 '50'으로 copy되도록 수정하였으나, 여기서 한번도 필터링함
			boolean isNotice = CoConstDef.CD_DTL_OBLIGATION_NOTICE.equals(bean.getObligationType());
			
			if(!isDisclosure && !isNotice) {
				continue;
			}
			
			// 2017.07.05
			// Accompanied with source code 의 경우
			// 소스공개여부와 상관없이 모두 소스공개가 필요한 oss table에 표시
			if(CoConstDef.CD_DTL_NOTICE_TYPE_ACCOMPANIED.equals(ossNotice.getNoticeType())) {
				isDisclosure = true;
			}
			
			// 2017.05.16 add by yuns end
			boolean addDisclosure = isDisclosure && srcInfo.containsKey(componentKey);
			boolean addNotice = !isDisclosure && noticeInfo.containsKey(componentKey);
			
			if(addDisclosure) {
				ossComponent = srcInfo.get(componentKey);
			} else if(addNotice) {
				ossComponent = noticeInfo.get(componentKey);
			} else {
				ossComponent = bean;
			}
					
			// 라이선스 정보 생성
			OssComponentsLicense license = new OssComponentsLicense();
			license.setLicenseId(bean.getLicenseId());
			license.setLicenseName(bean.getLicenseName());
			license.setLicenseText(bean.getLicenseText());
			license.setAttribution(bean.getAttribution());
			// 하나의 oss에 대해서 동일한 LICENSE가 복수 표시되는 현상 
			// 일단 여기서 막는다. (쿼리가 잘못된 건지, DATA가 꼬이는건지 모르겠음)
			if(!checkLicenseDuplicated(ossComponent.getOssComponentsLicense(), license)) {
				ossComponent.addOssComponentsLicense(license);
				// OSS의 Copyright text를 수정하였음에도 Packaging > Notice Preview에 업데이트 안 됨.
				// MULTI LICENSE를 가지는 oss의 개별로 추가된 copyright의 경우, Identification Confirm시에 DB에 업데이트한 정보를 기준으로 추출되기 때문에, preview 단계에서 오류가 발견되어 수정하여도 반영되지 않는다
				// verification단계에서의 oss_component_license는 oss_license의 license등록 순번을 가지고 있지 않기 때문에 (exclude된 license는 이관하지 않음)
				// 여기서 oss id와 license id를 이용하여 찾는다.
				// 동이한 라이선스를 or 구분으로 여러번 정의한 경우 문제가 될 수 있으나, 동일한 oss의 동일한 license의 경우 같은 copyright를 추가한다는 전제하에 적용함 (이부분에서 추가적인 이슉가 발생할 경우 대응방법이 복잡해짐)
				 if(CoConstDef.FLAG_NO.equals(ossComponent.getAdminCheckYn())) {
					 bean.setOssCopyright(findAddedOssCopyright(bean.getOssId(), bean.getLicenseId(), bean.getOssCopyright()));
				
					 // multi license 추가 copyright
					 if(!isEmpty(bean.getOssCopyright())) {
						 String addCopyright = avoidNull(ossComponent.getCopyrightText());
						
						 if(!isEmpty(ossComponent.getCopyrightText())) {
							 addCopyright += "\r\n";
						 }
						
						 addCopyright += bean.getOssCopyright();
						 ossComponent.setCopyrightText(addCopyright);
					 }
				 }
			}
			
			if(isDisclosure) {
				if(addDisclosure) {
					srcInfo.replace(componentKey, ossComponent);
				} else {
					srcInfo.put(componentKey, ossComponent);
				}
			} else {
				if(addNotice) {
					noticeInfo.replace(componentKey, ossComponent);
				} else {
					noticeInfo.put(componentKey, ossComponent);
				}
			}
			
			if(!licenseInfo.containsKey(license.getLicenseName())) {
				licenseInfo.put(license.getLicenseName(), license);
			}
		}
		// CLASS 파일만 등록한 경우 라이선스 정보만 추가한다.
		// OSS NAME을 하이픈 ('-') 으로 등록한 경우 (고지문구에 라이선스만 추가)
		List<OssComponents> addOssComponentList = selfCheckMapper.selectVerificationNoticeClassAppend(ossNotice);

		if(addOssComponentList != null) {
			for(OssComponents bean : addOssComponentList) {
				String componentKey = (bean.getOssName() + "|" + bean.getOssVersion()).toUpperCase();
				
				if("-".equals(bean.getOssName())) {
					componentKey += dashSeq++;
				}
				
				OssComponentsLicense license = new OssComponentsLicense();
				license.setLicenseId(bean.getLicenseId());
				license.setLicenseName(bean.getLicenseName());
				license.setLicenseText(bean.getLicenseText());
				license.setAttribution(bean.getAttribution());
				bean.addOssComponentsLicense(license);
				
				if(CoConstDef.CD_DTL_OBLIGATION_DISCLOSURE.equals(bean.getObligationType())
						|| CoConstDef.CD_DTL_NOTICE_TYPE_ACCOMPANIED.equals(ossNotice.getNoticeType())) { // Accompanied with source code 의 경우 source 공개 의무
					srcInfo.put(componentKey, bean);
				} else {
					noticeInfo.put(componentKey, bean);
				}
				
				if(!licenseInfo.containsKey(license.getLicenseName())) {
					licenseInfo.put(componentKey, license);
				}
			}
		}
		
		boolean isTextNotice = "text".equals(ossNotice.getFileType());
		
		Map<String, String> ossAttributionMap = new HashMap<>();
		// 개행처리 및 velocity용 list 생성
		List<OssComponents> noticeList = new ArrayList<>();
		
		for(OssComponents bean : noticeInfo.values()) {
			if(isTextNotice) {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getLicenseText()))));
				bean.setOssAttribution(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getOssAttribution()))));
			} else {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getLicenseText()))));
				bean.setOssAttribution(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getOssAttribution()))));
			}

			if(!isEmpty(bean.getOssAttribution()) && !ossAttributionMap.containsKey(avoidNull(bean.getOssName()) + "_" + avoidNull(bean.getOssVersion()))) {
				ossAttributionMap.put(avoidNull(bean.getOssName()) + "_" + avoidNull(bean.getOssVersion()), avoidNull(bean.getOssName(), "") + "__" + bean.getOssAttribution());
			}
			
			if(!isEmpty(bean.getOssName())) {
				bean.setOssName(StringUtil.replaceHtmlEscape(bean.getOssName()));
			}
			
			noticeList.add(bean);
		}
		List<OssComponents> srcList = new ArrayList<>();
		
		for(OssComponents bean : srcInfo.values()) {
			if(isTextNotice) {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getLicenseText()))));
				bean.setOssAttribution(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getOssAttribution()))));
			} else {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getLicenseText()))));
				bean.setOssAttribution(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getOssAttribution()))));
			}
			

			if(!isEmpty(bean.getOssAttribution()) && !ossAttributionMap.containsKey(avoidNull(bean.getOssName()) + "_" + avoidNull(bean.getOssVersion()))) {
				ossAttributionMap.put(avoidNull(bean.getOssName()) + "_" + avoidNull(bean.getOssVersion()), avoidNull(bean.getOssName(), "") + "__" + bean.getOssAttribution());
			}
			
			if(!isEmpty(bean.getOssName())) {
				bean.setOssName(StringUtil.replaceHtmlEscape(bean.getOssName()));
			}

			srcList.add(bean);
		}
		
		List<OssComponentsLicense> licenseList = new ArrayList<>();
		List<OssComponentsLicense> licenseListUrls = new ArrayList<>(); //simple version용
		List<OssComponentsLicense> attributionList = new ArrayList<>();
		List<String> ossAttributionList = new ArrayList<>();
		
		// 정렬
		TreeMap<String, OssComponentsLicense> licenseTreeMap = new TreeMap<>( licenseInfo );
		
		for(OssComponentsLicense bean : licenseTreeMap.values()) {
			if(isTextNotice) {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.unescapeHtml(avoidNull(bean.getLicenseText()))));
			} else {
				bean.setCopyrightText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getCopyrightText()))));
				bean.setLicenseText(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getLicenseText()))));
			}
			
			// 배포사이트 license text url
			licenseList.add(bean);
			
			if(CoConstDef.FLAG_YES.equals(ossNotice.getSimpleNoticeFlag())) {
				LicenseMaster licenseBean = CoCodeManager.LICENSE_INFO_BY_ID.get(bean.getLicenseId());
				
				if(licenseBean != null) {
//					String simpleLicenseFileName = !isEmpty(licenseBean.getShortIdentifier()) ? licenseBean.getShortIdentifier() : licenseBean.getLicenseNameTemp();
//					String distributeUrl = CoCodeManager.getCodeExpString(CoConstDef.CD_DISTRIBUTE_CODE, CoConstDef.CD_DTL_DISTRIBUTE_LGE);
//					simpleLicenseFileName = simpleLicenseFileName.replaceAll(" ", "_").replaceAll("/", "_") + ".html";
//					distributeUrl += "/license/" + simpleLicenseFileName;
					boolean distributionFlag = CommonFunction.propertyFlagCheck("distribution.use.flag", CoConstDef.FLAG_YES);
					licenseBean.setDomain(ossNotice.getDomain());
					
					bean.setWebpage(CommonFunction.makeLicenseInternalUrl(licenseBean, distributionFlag));
					licenseListUrls.add(bean);
				}
			}

			if(!isEmpty(bean.getAttribution())) {
				bean.setAttribution(CommonFunction.lineReplaceToBR(StringEscapeUtils.escapeHtml(avoidNull(bean.getAttribution()))));
				attributionList.add(bean);
			}
		}

		TreeMap<String, String> ossAttributionTreeMap = new TreeMap<>( ossAttributionMap );
		ossAttributionList.addAll(ossAttributionTreeMap.values());
		
		// 배포 사이트 구분에 따라 참조 코드가 달라짐
		String noticeInfoCode = CoConstDef.CD_DTL_DISTRIBUTE_SKS.equals(avoidNull(distributeSite, CoConstDef.CD_DTL_DISTRIBUTE_LGE)) ? CoConstDef.CD_NOTICE_DEFAULT_SKS : CoConstDef.CD_NOTICE_DEFAULT;

		noticeType = avoidNull(ossNotice.getNoticeType(), CoConstDef.CD_DTL_NOTICE_TYPE_GENERAL);
		
		String companyNameFull = ossNotice.getCompanyNameFull();
		String distributionSiteUrl = ossNotice.getDistributionSiteUrl();
		String email = ossNotice.getEmail();
		String appendedContentsTEXT = ossNotice.getAppendedTEXT();
		String appendedContents = ossNotice.getAppended();
		
		if(!isEmpty(distributionSiteUrl) && !(distributionSiteUrl.startsWith("http://") || distributionSiteUrl.startsWith("https://") || distributionSiteUrl.startsWith("ftp://"))) {
			distributionSiteUrl = "http://" + distributionSiteUrl;
		}
		model.put("noticeType", noticeType);
		model.put("noticeTitle", CommonFunction.getNoticeFileName(prjId, prjName, prjVersion, CommonFunction.getCurrentDateTime("yyMMdd"), ossNotice.getFileType()));
		model.put("companyNameFull", companyNameFull);
		model.put("distributionSiteUrl", distributionSiteUrl);
		model.put("email", email);
		model.put("noticeObligationSize", noticeList.size());
		model.put("disclosureObligationSize", srcList.size());
		model.put("noticeObligationList", noticeList);
		model.put("disclosureObligationList", srcList);
		/* ui 개선버전으로 신규 추가된 flag */
		model.put("editNoticeYn", ossNotice.getEditNoticeYn());
		model.put("editCompanyYn", ossNotice.getEditCompanyYn());
		model.put("editDistributionSiteUrlYn", ossNotice.getEditDistributionSiteUrlYn());
		model.put("editEmailYn", ossNotice.getEditEmailYn());
		model.put("hideOssVersionYn", ossNotice.getHideOssVersionYn());
		model.put("editAppendedYn", ossNotice.getEditAppendedYn());
		
		/*//ui 개선버전으로 신규 추가된 flag */
		if(CoConstDef.FLAG_YES.equals(ossNotice.getSimpleNoticeFlag())) {
			model.put("licenseListUrls", licenseListUrls);
		} else {
			model.put("licenseList", licenseList);
		}
		
		model.put("attributionList", attributionList.isEmpty() ? null : attributionList);
		model.put("ossAttributionList", ossAttributionList.isEmpty() ? null : ossAttributionList);
		
		if("text".equals(ossNotice.getFileType())){
			model.put("appended", avoidNull(appendedContentsTEXT, "").replaceAll("&nbsp;", " "));
		} else {
			model.put("appended", appendedContents);
		}

		if("text".equals(ossNotice.getFileType())){
			model.put("templateURL", CoCodeManager.getCodeExpString(noticeInfoCode, CoConstDef.CD_DTL_NOTICE_TEXT_TEMPLATE));
		} else {
			model.put("templateURL", CoCodeManager.getCodeExpString(noticeInfoCode, CoConstDef.CD_DTL_NOTICE_DEFAULT_TEMPLATE));
		}

		model.put("addOssComponentList", addOssComponentList);
		model.put("isSimpleNotice", avoidNull(ossNotice.getIsSimpleNotice(), CoConstDef.FLAG_NO));
		
		return model;
	}

	private boolean checkLicenseDuplicated(List<OssComponentsLicense> ossComponentsLicense,
			OssComponentsLicense license) {
		if(ossComponentsLicense != null) {
			for(OssComponentsLicense bean : ossComponentsLicense) {
				if(bean.getLicenseId().equals(license.getLicenseId())) {
					return true;
				}
			}
		}
		
		return false;
	}

	public int allowDownloadMultiFlagToBitFlag(Project project) {
		int bitFlag = 1;
		
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadNoticeHTMLYn())) {
			bitFlag |= CoConstDef.FLAG_A;
		}
			
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadNoticeTextYn())) {
			bitFlag |= CoConstDef.FLAG_B;
		}
		
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSimpleHTMLYn())) {
			bitFlag |= CoConstDef.FLAG_C;
		}
			
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSimpleTextYn())) {
			bitFlag |= CoConstDef.FLAG_D;
		}
			
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXSheetYn())) {
			bitFlag |= CoConstDef.FLAG_E;
		}
			
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXRdfYn())) {
			bitFlag |= CoConstDef.FLAG_F;
		}
			
		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXTagYn())) {
			bitFlag |= CoConstDef.FLAG_G;
		}

		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXJsonYn())) {
			bitFlag |= CoConstDef.FLAG_H;
		}

		if(CoConstDef.FLAG_YES.equals(project.getAllowDownloadSPDXYamlYn())) {
			bitFlag |= CoConstDef.FLAG_I;
		}
		
		return bitFlag;
	}

	private String findAddedOssCopyright(String ossId, String licenseId, String ossCopyright) {
		if(!isEmpty(ossId) && !isEmpty(licenseId)) {
			OssMaster bean = CoCodeManager.OSS_INFO_BY_ID.get(ossId);
			if (bean != null) {
				for(OssLicense license : bean.getOssLicenses()) {
					if(licenseId.equals(license.getLicenseId()) && !isEmpty(license.getOssCopyright())) {
						return license.getOssCopyright();
					}
				}
			}
		}
		
		return ossCopyright;
	}
	
	private String getPackageFileName(String prjName, String prjVersion, String orgFileName, String fileSeq) {
		String fileName = prjName;
		
		if(!isEmpty(prjVersion)) {
			fileName += "_" + prjVersion;
		}
		
		if(!isEmpty(fileSeq)){
			fileName += "_" + fileSeq;
		}
		
		// file명에 사용할 수 없는 특수문자 체크
		if(!FileUtil.isValidFileName(fileName)) {
			fileName = FileUtil.makeValidFileName(fileName, "_");
		}
		
		String fileExt = FilenameUtils.getExtension(orgFileName);
		
		if(orgFileName.toLowerCase().endsWith(".tgz.gz")) {
			fileExt = "tgz.gz";
		} else if(orgFileName.toLowerCase().endsWith(".tar.bz2")) {
			fileExt = "tar.bz2";
		} else if(orgFileName.toLowerCase().endsWith(".tar.gz")) {
			fileExt = "tar.gz";
		}
		
		if(fileExt.startsWith(".")) {
			fileExt = fileExt.substring(1);
		}
		
		return fileName + "." + fileExt;
	}

	@Override
	public String getNoticeHtmlFileForPreview(OssNotice ossNotice) throws IOException {
		Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		
		return getNoticeVelocityTemplateFileForPreview(getNoticeHtml(ossNotice), prjInfo, ossNotice.getFileType(), ossNotice.getSimpleNoticeFlag());	
	}
	
	private String getNoticeVelocityTemplateFileForPreview(String contents, Project project, String fileType, String simpleFlag) throws IOException {
		return getNoticeVelocityTemplateFileForPreview(contents, project, fileType, simpleFlag, false);
	}
	private String getNoticeVelocityTemplateFileForPreview(String contents, Project project, String fileType, String simpleFlag, boolean isConfirm) throws IOException {
		String fileId = "";
		String filePath = "";
		String fileName = "";
		// Text 형식 OSS 고지문 생성 시 개행문자 변경
		// System.getProperty("line.separator") => "\n" => "\r\n" 변경 
		String line = "\r\n";
		if(fileType == "text"){
			fileId = "";
			filePath = NOTICE_PATH + ( isConfirm ? "/" : "/preview/") + project.getPrjId();
			fileName = (CoConstDef.FLAG_YES.equals(simpleFlag) ? "simple_" : "") + CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), ( isConfirm ? CommonFunction.getCurrentDateTime("yyMMdd") : DateUtil.getCurrentDateTime(DateUtil.DATE_HMS_PATTERN) ), fileType);
			contents = contents.replace("\n", line).replace(",)", ")").replace("<br>", line).replace("&copy;", "©").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "\'");
		} else {
			fileId = "";
			filePath = NOTICE_PATH + ( isConfirm ? "/" : "/preview/") + project.getPrjId();
			fileName = (CoConstDef.FLAG_YES.equals(simpleFlag) ? "simple_" : "") + CommonFunction.getNoticeFileName(project.getPrjId(), project.getPrjName(), project.getPrjVersion(), ( isConfirm ? CommonFunction.getCurrentDateTime("yyMMdd") : DateUtil.getCurrentDateTime(DateUtil.DATE_HMS_PATTERN) ), fileType);
			
			// custom edit를 사용하고, packaging confirm 인 경우 이면서 simple인 경우
			// license text 부분만 다시 변경한다.
			if(isConfirm && CoConstDef.FLAG_YES.equals(simpleFlag) && CoConstDef.FLAG_YES.equals(project.getUseCustomNoticeYn())) {
				// 이미 생성된 고지문구 파일의 내용을 가져온다.
				T2File defaultNoticeFileInfo = fileService.selectFileInfo(project.getNoticeFileId());
				
				if(defaultNoticeFileInfo != null) {
					File noticeFile = new File(defaultNoticeFileInfo.getLogiPath() + "/" + defaultNoticeFileInfo.getLogiNm());
					if(noticeFile.exists()) {
						Document doc = Jsoup.parse(noticeFile, "UTF8");
						Document doc2 = Jsoup.parse(contents);
						
						doc.select("body > p.bdTop.license").remove();
						doc.select("body").append(doc2.select("body > p.bdTop.license").toString());
						doc.select("body").append(doc2.select("body > ul.bdTop2.license").toString());
						
						contents = doc.toString();
					}
				}
			}
		}
		if(FileUtil.writhFile(filePath, fileName, contents)) {
			// 파일 등록
			fileId = fileService.registFileDownload(filePath, fileName, fileName);
		}
		return fileId;
	}
	
	@Override
	public String getNoticeTextFileForPreview(OssNotice ossNotice) throws IOException {
		Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		
		return getNoticeVelocityTemplateFileForPreview(getNoticeHtml(ossNotice), prjInfo, ossNotice.getFileType(), ossNotice.getSimpleNoticeFlag());	
	}
	
	@Override
	public String getNoticeTextFileForPreview(OssNotice ossNotice, boolean isConfirm) throws IOException {
		Project prjInfo = getProjectBasicInfo(ossNotice.getPrjId());
		
		return getNoticeVelocityTemplateFileForPreview(getNoticeHtml(ossNotice), prjInfo, ossNotice.getFileType(), ossNotice.getSimpleNoticeFlag(), isConfirm);	
	}

}
