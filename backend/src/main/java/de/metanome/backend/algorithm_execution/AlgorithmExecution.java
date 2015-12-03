/*
 * Copyright 2015 by the Metanome project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.metanome.backend.algorithm_execution;

import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.algorithm_execution.FileGenerator;
import de.metanome.algorithm_integration.configuration.ConfigurationValue;
import de.metanome.algorithm_integration.input.FileInputGenerator;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.input.TableInputGenerator;
import de.metanome.algorithm_integration.results.JsonConverter;
import de.metanome.backend.helper.ConfigurationValueMixIn;
import de.metanome.backend.helper.FileInputGeneratorMixIn;
import de.metanome.backend.helper.RelationalInputGeneratorMixIn;
import de.metanome.backend.helper.TableInputGeneratorMixIn;
import de.metanome.backend.resources.AlgorithmResource;
import de.metanome.backend.result_receiver.ResultCache;
import de.metanome.backend.result_receiver.ResultCounter;
import de.metanome.backend.result_receiver.ResultPrinter;
import de.metanome.backend.result_receiver.ResultReceiver;
import de.metanome.backend.results_db.EntityStorageException;
import de.metanome.backend.results_db.ExecutionSetting;
import de.metanome.backend.results_db.HibernateUtil;
import de.metanome.backend.results_db.Input;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes care of executing algorithms with specified settings in a designated/separate process
 */
public class AlgorithmExecution {

  /**
   * Builds an {@link de.metanome.backend.algorithm_execution.AlgorithmExecutor} with the given
   * execution settings.
   *
   * @param executionSetting the execution setting
   * @return an {@link de.metanome.backend.algorithm_execution.AlgorithmExecutor}
   * @throws java.io.FileNotFoundException        when the result files cannot be opened
   * @throws java.io.UnsupportedEncodingException when the temp files cannot be opened
   */
  protected static AlgorithmExecutor buildExecutor(ExecutionSetting executionSetting)
    throws FileNotFoundException, UnsupportedEncodingException {
    FileGenerator fileGenerator = new TempFileGenerator();
    String identifier = executionSetting.getExecutionIdentifier();

    ResultReceiver resultReceiver;
    if (executionSetting.getCacheResults()) {
      resultReceiver = new ResultCache(identifier);
    } else if (executionSetting.getCountResults()) {
      resultReceiver = new ResultCounter(identifier);
    } else {
      resultReceiver = new ResultPrinter(identifier);
    }

    AlgorithmExecutor executor =
      new AlgorithmExecutor(resultReceiver, fileGenerator);
    executor.setResultPathPrefix(resultReceiver.getOutputFilePathPrefix());
    return executor;
  }

  /**
   * Generates a list of ConfigurationValues from an List of ConfigurationValue json-strings
   *
   * @param parameterValuesJson List of parameter values in json format
   * @return a list of all configuration values
   */
  public static List<ConfigurationValue> parseConfigurationValues(List<String> parameterValuesJson) {
    JsonConverter<ConfigurationValue> jsonConverter = new JsonConverter<>();
    jsonConverter.addMixIn(FileInputGenerator.class, FileInputGeneratorMixIn.class);
    jsonConverter.addMixIn(TableInputGenerator.class, TableInputGeneratorMixIn.class);
    jsonConverter.addMixIn(RelationalInputGenerator.class, RelationalInputGeneratorMixIn.class);
    jsonConverter.addMixIn(ConfigurationValue.class, ConfigurationValueMixIn.class);

    List<ConfigurationValue> parameterValues = new ArrayList<>();
    for (String json : parameterValuesJson) {
      try {
        parameterValues.add(jsonConverter.fromJsonString(json, ConfigurationValue.class));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    return parameterValues;
  }

  /**
   * Generates a list of Inputs from an List of Input json-strings
   *
   * @param inputsJson inputs in json format
   * @return a list of inputs
   */
  public static List<Input> parseInputs(List<String> inputsJson) {
    JsonConverter<Input> jsonConverterInput = new JsonConverter<>();
    List<Input> inputs = new ArrayList<>();

    for (String json : inputsJson) {
      try {
        inputs.add(jsonConverterInput.fromJsonString(json, Input.class));
      } catch (IOException e1) {
        e1.printStackTrace();
      }
    }

    return inputs;
  }

  /**
   * Uses Algorithm and Execution Identifier (parsed from args[]) to load instances of Algorithm and
   * ExecutionSetting from the database, which are then used to execute the specified Algorithm with
   * the specified setting in the designated process
   *
   * @param args the program parameters
   */
  public static void main(String args[]) {
    Long algorithmId = Long.valueOf(args[0]);
    String executionIdentifier = args[1];

    // Get the algorithm object
    AlgorithmResource algorithmResource = new AlgorithmResource();
    de.metanome.backend.results_db.Algorithm algorithm = algorithmResource.get(algorithmId);

    // Get the execution setting from hibernate
    Session session = HibernateUtil.getSessionFactory().openSession();
    Criteria cr2 = session.createCriteria(ExecutionSetting.class);
    cr2.add(Restrictions.eq("executionIdentifier", executionIdentifier));
    ExecutionSetting executionSetting = (ExecutionSetting) cr2.list().get(0);

    // Parse the parameters
    List<ConfigurationValue> parameters = parseConfigurationValues(executionSetting.getParameterValuesJson());
    List<Input> inputs = parseInputs(executionSetting.getInputsJson());

    session.close();

    // Get the algorithm executor
    try {
      AlgorithmExecutor executor = buildExecutor(executionSetting);
      executor
        .executeAlgorithm(algorithm, parameters, inputs, executionIdentifier,
          executionSetting);

      executor.close();
    } catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException
      | InvocationTargetException | NoSuchMethodException | AlgorithmExecutionException
      | EntityStorageException e) {
      e.printStackTrace();
    }
    System.exit(0);

    //have method create ExecutionSetting and do parsing that is currently being done in execute algorithm
    //goto Hibernate find infos with id - execute found jar ... write results to file/db -> finish
  }

}