package io.mosip.commons.khazana.impl;


import static io.mosip.commons.khazana.config.LoggerConfiguration.REGISTRATIONID;
import static io.mosip.commons.khazana.config.LoggerConfiguration.SESSIONID;
import static io.mosip.commons.khazana.constant.KhazanaConstant.TAGS_FILENAME;
import static io.mosip.commons.khazana.constant.KhazanaErrorCodes.OBJECT_STORE_NOT_ACCESSIBLE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.ObjectTagging;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.SetObjectTaggingRequest;
import com.amazonaws.services.s3.model.Tag;

import io.mosip.commons.khazana.config.LoggerConfiguration;
import io.mosip.commons.khazana.dto.ObjectDto;
import io.mosip.commons.khazana.exception.ObjectStoreAdapterException;
import io.mosip.commons.khazana.spi.ObjectStoreAdapter;
import io.mosip.commons.khazana.util.ObjectStoreUtil;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;

@Service
@Qualifier("S3Adapter")
public class S3Adapter implements ObjectStoreAdapter {

    private final Logger LOGGER = LoggerConfiguration.logConfig(S3Adapter.class);

    @Value("${object.store.s3.accesskey:accesskey:accesskey}")
    private String accessKey;
    @Value("${object.store.s3.secretkey:secretkey:secretkey}")
    private String secretKey;
    @Value("${object.store.s3.url:null}")
    private String url;

    @Value("${object.store.s3.region:null}")
    private String region;

    @Value("${object.store.s3.readlimit:10000000}")
    private int readlimit;

    @Value("${object.store.connection.max.retry:20}")
    private int maxRetry;

    @Value("${object.store.max.connection:200}")
    private int maxConnection;
    
    @Value("${object.store.s3.use.account.as.bucketname:false}")
    protected boolean useAccountAsBucketname;

    private static final String SEPARATOR = "/";

    private int retry = 0;
    
    private AmazonS3 connection = null;

	@Override
	public InputStream getObject(String account, String container, String source, String process, String objectName) {
		return getObject(getFinalObjectName(container, source, process, objectName, useAccountAsBucketname),
				getBucketName(account, container, useAccountAsBucketname), container);
	}
    
	private InputStream getObject(String finalObjectName, String bucketName, String container) {
		S3Object s3Object = null;
		try {
			s3Object = getConnection(bucketName).getObject(bucketName, finalObjectName);
			if (s3Object != null) {
				ByteArrayOutputStream temp = new ByteArrayOutputStream();
				IOUtils.copy(s3Object.getObjectContent(), temp);
				ByteArrayInputStream bis = new ByteArrayInputStream(temp.toByteArray());
				return bis;
			}
		} catch (Exception e) {
			LOGGER.error(SESSIONID, REGISTRATIONID, "Exception occured to getObject for : " + container,
					ExceptionUtils.getStackTrace(e));
			throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(),
					OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
		} finally {
			if (s3Object != null) {
				try {
					s3Object.close();
				} catch (IOException e) {
					LOGGER.error(SESSIONID, REGISTRATIONID, "IO occured : " + container,
							ExceptionUtils.getStackTrace(e));
				}
			}
		}
		return null;
	}

    @Override
    public boolean exists(String account, String container, String source, String process, String objectName) {
    	 String finalObjectName=null;
    	 String bucketName=null;
    	if(useAccountAsBucketname) {
    		 finalObjectName = ObjectStoreUtil.getName(container,source, process, objectName);
    		 bucketName=account;
    	}else {
    		 finalObjectName = ObjectStoreUtil.getName(source, process, objectName);
    		 bucketName=container;
    	}
        ObjectMetadata objectMetadata = null;
    	try {
            objectMetadata = getConnection(bucketName).getObjectMetadata(bucketName, finalObjectName);
        } catch (AmazonS3Exception e) {
    	    if (e.getStatusCode() == HttpStatus.NOT_FOUND.value())
                LOGGER.error(SESSIONID, REGISTRATIONID, container,"Object not found in object store");
            else
                LOGGER.error(SESSIONID, REGISTRATIONID, container,ExceptionUtils.getStackTrace(e));
        }
        return objectMetadata != null;
    }

    @Override
    public boolean putObject(String account, final String container, String source, String process, String objectName, InputStream data) {
    	 String finalObjectName=null;
    	 String bucketName=null;
    	if(useAccountAsBucketname) {
    		 finalObjectName = ObjectStoreUtil.getName(container,source, process, objectName);
    		 bucketName=account;
    	}else {
    		 finalObjectName = ObjectStoreUtil.getName(source, process, objectName);
    		 bucketName=container;
    	}
        AmazonS3 connection = getConnection(bucketName);
        if (!connection.doesBucketExistV2(bucketName))
            connection.createBucket(bucketName);

     
        connection.putObject(bucketName, finalObjectName, data, null);
        return true;
    }

    @Override
    public Map<String, Object> addObjectMetaData(String account, String container, String source, String process,
                                                 String objectName, Map<String, Object> metadata) {
        S3Object s3Object = null;
        try {
        	 String finalObjectName=null;
        	 String bucketName=null;
        	if(useAccountAsBucketname) {
        		 finalObjectName = ObjectStoreUtil.getName(container,source, process, objectName);
        		 bucketName=account;
        	}else {
        		 finalObjectName = ObjectStoreUtil.getName(source, process, objectName);
        		 bucketName=container;
        	}
            ObjectMetadata objectMetadata = new ObjectMetadata();
            //changed usermetadata getting  overrided
            //metadata.entrySet().stream().forEach(m -> objectMetadata.addUserMetadata(m.getKey(), m.getValue() != null ? m.getValue().toString() : null));
           
            s3Object = getConnection(bucketName).getObject(bucketName, finalObjectName);
            if (s3Object.getObjectMetadata() != null && s3Object.getObjectMetadata().getUserMetadata() != null)
                s3Object.getObjectMetadata().getUserMetadata().entrySet().forEach(m -> objectMetadata.addUserMetadata(m.getKey(), m.getValue()));
            metadata.entrySet().stream().forEach(m -> objectMetadata.addUserMetadata(m.getKey(), m.getValue() != null ? m.getValue().toString() : null));
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, finalObjectName, s3Object.getObjectContent(), objectMetadata);
            putObjectRequest.getRequestClientOptions().setReadLimit(readlimit);
            getConnection(bucketName).putObject(putObjectRequest);
            return metadata;
        } catch (Exception e) {
            LOGGER.error(SESSIONID, REGISTRATIONID,"Exception occured to addObjectMetaData for : " + container, ExceptionUtils.getStackTrace(e));
            metadata = null;
            throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(), OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
        } finally {
            try {
                if (s3Object != null)
                    s3Object.close();
            } catch (IOException e) {
                LOGGER.error(SESSIONID, REGISTRATIONID,"IO occured : " + container, ExceptionUtils.getStackTrace(e));
            }
        }
    }

    @Override
    public Map<String, Object> addObjectMetaData(String account, String container, String source, String process,
                                                 String objectName, String key, String value) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(key, value);
        String finalObjectName=null;
   	    
   	   if(useAccountAsBucketname) {
   		  finalObjectName = ObjectStoreUtil.getName(container,source, process, objectName);
   		
   	   }else {
   		 finalObjectName = ObjectStoreUtil.getName(source, process, objectName);
   		}
       
        return addObjectMetaData(account, container, source, process, finalObjectName, meta);
    }

    @Override
    public Map<String, Object> getMetaData(String account, String container, String source, String process,
                                           String objectName) {
        S3Object s3Object = null;
        try {
        	 String finalObjectName=null;
        	 String bucketName=null;
        	if(useAccountAsBucketname) {
        		 finalObjectName = ObjectStoreUtil.getName(container,source, process, objectName);
        		 bucketName=account;
        	}else {
        		 finalObjectName = ObjectStoreUtil.getName(source, process, objectName);
        		 bucketName=container;
        	}
            Map<String, Object> metaData = new HashMap<>();

            s3Object = getConnection(bucketName).getObject(bucketName, finalObjectName);
            ObjectMetadata objectMetadata = s3Object.getObjectMetadata();
            if (objectMetadata != null && objectMetadata.getUserMetadata() != null)
                objectMetadata.getUserMetadata().entrySet().forEach(entry -> metaData.put(entry.getKey(), entry.getValue()));
            return metaData;
        } catch (Exception e) {
            LOGGER.error(SESSIONID, REGISTRATIONID,"Exception occured to getMetaData for : " + container, ExceptionUtils.getStackTrace(e));
            throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(), OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
        } finally {
            try {
                if (s3Object != null)
                    s3Object.close();
            } catch (IOException e) {
                LOGGER.error(SESSIONID, REGISTRATIONID,"IO occured : " + container, ExceptionUtils.getStackTrace(e));
            }
        }
    }

    @Override
    public Integer incMetadata(String account, String container, String source, String process, String objectName, String metaDataKey) {
        Map<String, Object> metadata = getMetaData(account, container, source, process, objectName);
        if (metadata.get(metaDataKey) != null) {
            metadata.put(metaDataKey, Integer.valueOf(metadata.get(metaDataKey).toString()) + 1);
            addObjectMetaData(account, container, source, process, objectName, metadata);
            return Integer.valueOf(metadata.get(metaDataKey).toString());
        }
        return null;
    }

    @Override
    public Integer decMetadata(String account, String container, String source, String process, String objectName, String metaDataKey) {
        Map<String, Object> metadata = getMetaData(account, container, source, process, objectName);
        if (metadata.get(metaDataKey) != null) {
        	metadata.put(metaDataKey, Integer.valueOf(metadata.get(metaDataKey).toString()) - 1);
            addObjectMetaData(account, container, source, process, objectName, metadata);
            return Integer.valueOf(metadata.get(metaDataKey).toString());
        }
        return null;
    }

    @Override
    public boolean deleteObject(String account, String container, String source, String process, String objectName) {
		String bucketName = getBucketName(account, container, useAccountAsBucketname);
		getConnection(bucketName).deleteObject(bucketName,
				getFinalObjectName(container, source, process, objectName, useAccountAsBucketname));
		return true;
    }

    /**
     * Removing container not supported in S3Adapter
     *
     * @param account
     * @param container
     * @param source
     * @param process
     * @return
     */
    @Override
    public boolean removeContainer(String account, String container, String source, String process) {
        return false;
    }

    /**
     * Not Supported in S3Adapter
     *
     * @param account
     * @param container
     * @param source
     * @param process
     * @return
     */
    @Override
    public boolean pack(String account, String container, String source, String process) {
        return false;
    }

    protected AmazonS3 getConnection(String bucketName) {
        try {
            if (connection != null) {
                // test connection once before returning it
                connection.doesBucketExistV2(bucketName);
                return connection;
            }
        } catch (Exception e) {
            LOGGER.error(SESSIONID, REGISTRATIONID,"Exception occured while using existing connection for " + bucketName +". Will try to create new. Retry count : " + retry, ExceptionUtils.getStackTrace(e));
        }
        try {
            AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
            connection = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                    .enablePathStyleAccess().withClientConfiguration(new ClientConfiguration().withMaxConnections(maxConnection)
                            .withMaxErrorRetry(maxRetry))
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(url, region)).build();
            // test connection once before returning it
            connection.doesBucketExistV2(bucketName);
            retry = 0;
            return connection;

        } catch (Exception e) {
            if (retry >= maxRetry) {
                LOGGER.error(SESSIONID, REGISTRATIONID,"Maximum retry limit exceeded. Could not obtain connection for "+ bucketName +". Retry count :" + retry, ExceptionUtils.getStackTrace(e));
                throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(), OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
            } else {
                retry = retry + 1;
                LOGGER.error(SESSIONID, REGISTRATIONID,"Exception occured while obtaining connection for "+ bucketName +". Will try again. Retry count : " + retry, ExceptionUtils.getStackTrace(e));
                getConnection(bucketName);
            }
        }
        return null;
    }

    public List<ObjectDto> getAllObjects(String account, String id) {

        String searchPattern = id + SEPARATOR;
        List<S3ObjectSummary> os = null;
   	   if(useAccountAsBucketname)
           os = getConnection(account).listObjects(account, searchPattern).getObjectSummaries();
   	   else
           os = getConnection(id).listObjects(searchPattern).getObjectSummaries();

        if (os != null && os.size() > 0) {
            List<ObjectDto> objectDtos = new ArrayList<>();
            os.forEach(o -> {
                // ignore the Tag file
                String[] tempKeys = o.getKey().endsWith(TAGS_FILENAME) ? null : o.getKey().split("/");
                String[] keys = removeIdFromObjectPath(useAccountAsBucketname, tempKeys);
                if (ArrayUtils.isNotEmpty(keys)) {
                    ObjectDto objectDto = null;
                    switch (keys.length) {
                        case 1:
                            objectDto = new ObjectDto(null, null, keys[0], o.getLastModified());
                            break;
                        case 2:
                            objectDto = new ObjectDto(keys[0], null, keys[1], o.getLastModified());
                            break;
                        case 3:
                            objectDto = new ObjectDto(keys[0], keys[1], keys[2], o.getLastModified());
                            break;
                    }
                    if (objectDto != null)
                        objectDtos.add(objectDto);
                }
            });
            return objectDtos;
        }

        return null;
    }

    /**
     * If account is used as bucket name then first element of array is the packet id.
     * This method removes packet id from array so that path is same irrespective of useAccountAsBucketname is true or false
     *
     * @param useAccountAsBucketname
     * @param keys
     */
    private String[] removeIdFromObjectPath(boolean useAccountAsBucketname, String[] keys) {
        return (useAccountAsBucketname && ArrayUtils.isNotEmpty(keys)) ?
                (String[]) ArrayUtils.remove(keys, 0) : keys;
    }

	@Override
	public Map<String, String> addTags(String account, String container, Map<String, String> tags) {
		try {
	
        	 String bucketName=null;
        	 String finalObjectName=null;
        	if(useAccountAsBucketname) {
        		 bucketName=account;
        		 finalObjectName = ObjectStoreUtil.getName(container,null,TAGS_FILENAME);
        	}else {
        		 bucketName=container;
        		 finalObjectName = TAGS_FILENAME;
        	}
			AmazonS3 connection = getConnection(bucketName);
			if (!connection.doesBucketExistV2(bucketName))
	            connection.createBucket(bucketName);
			
			Map<String, String> existingMetadata=getTags(account, container);
			if(!connection.doesObjectExist(bucketName, finalObjectName)) {
				connection.putObject(bucketName, finalObjectName, "");
			}
			
			tags.entrySet().stream()
					.forEach(m -> existingMetadata.put(m.getKey(), m.getValue() != null ? m.getValue() : null));
			List<Tag> tagSet=new ArrayList<Tag>();
			for(Entry<String, String> entry:existingMetadata.entrySet()) {
				tagSet.add(new Tag(entry.getKey(), entry.getValue()));
			}
			ObjectTagging objectTagging=new ObjectTagging(tagSet);
			
			SetObjectTaggingRequest setObjectTaggingRequest=new SetObjectTaggingRequest(bucketName,finalObjectName,objectTagging);
			connection.setObjectTagging(setObjectTaggingRequest);
			

		} catch (Exception e) {
			LOGGER.error(SESSIONID, REGISTRATIONID, "Exception occured while addTags for : " + container,
					ExceptionUtils.getStackTrace(e));
			throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(),
					OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
		}
		return tags;
	}

	@Override
	public Map<String, String> getTags(String account, String container) {
		Map<String, String> objectTags = new HashMap<String, String>();
		try {
	
		 String bucketName=null;
		 String finalObjectName=null;
     	if(useAccountAsBucketname) {
     		 bucketName=account;
     		 finalObjectName = ObjectStoreUtil.getName(container,null,TAGS_FILENAME);
     	}else {
     		 bucketName=container;
     		 finalObjectName = TAGS_FILENAME;
     	}
		AmazonS3 connection = getConnection(bucketName);
		if(connection.doesObjectExist(bucketName, finalObjectName)) {
		
		GetObjectTaggingRequest getObjectTaggingRequest=new GetObjectTaggingRequest(bucketName,finalObjectName);
		
			GetObjectTaggingResult getObjectTaggingResult=connection.getObjectTagging(getObjectTaggingRequest);
		
		if(getObjectTaggingResult!=null) {
			List<Tag> tagSet = getObjectTaggingResult.getTagSet();
			if (tagSet != null) {
				for(Tag tag:tagSet) {
					objectTags.put(tag.getKey(), tag.getValue());
				}
			}
		  }
		}
		return objectTags;

		}catch(Exception e){
			LOGGER.error(SESSIONID, REGISTRATIONID, "Exception occured while getTags for : " + container,
					ExceptionUtils.getStackTrace(e));
			throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(),
					OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
		}
	
	}

	@Override
	public InputStream getObject(String account, String container, String source, String process, String objectName,
			boolean useAccountAsBucketname) {
		return getObject(getFinalObjectName(container, source, process, objectName, useAccountAsBucketname),
				getBucketName(account, container, useAccountAsBucketname), container);
	}

	@Override
	public boolean deleteObject(String account, String container, String source, String process, String objectName,
			boolean useAccountAsBucketname) {
		String bucketName = getBucketName(account, container, useAccountAsBucketname);
		getConnection(bucketName).deleteObject(bucketName,
				getFinalObjectName(container, source, process, objectName, useAccountAsBucketname));
		return true;
	}
	
	/**
	 * Gets the final object based on useAccountAsBucketname
	 * @param container
	 * @param source
	 * @param process
	 * @param objectName
	 * @param useAccountAsBucketname
	 * @return
	 */
	private String getFinalObjectName(String container, String source, String process, String objectName,
			boolean useAccountAsBucketname) {
		if (useAccountAsBucketname) {
			return ObjectStoreUtil.getName(container, source, process, objectName);
		}
		return ObjectStoreUtil.getName(source, process, objectName);
	}
	
	/**
	 * Gets the bucket name based on useAccountAsBucketname
	 * @param account
	 * @param container
	 * @param useAccountAsBucketname
	 * @return
	 */
	private String getBucketName(String account, String container, boolean useAccountAsBucketname) {
		return useAccountAsBucketname ? account : container;
	}

}
