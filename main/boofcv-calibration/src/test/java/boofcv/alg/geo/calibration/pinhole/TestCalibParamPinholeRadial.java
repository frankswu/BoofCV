/*
 * Copyright (c) 2011-2018, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.geo.calibration.pinhole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Peter Abeles
 */
public class TestCalibParamPinholeRadial {

	@Test
	public void numParameters() {
		for (int i = 0; i < 4; i++) {
			boolean skew = i % 2 == 0;
			boolean tangent = i / 2 == 0;

			CalibParamPinholeRadial param = new CalibParamPinholeRadial(skew, 2, tangent);

			int expected = 6;
			if( !skew ) expected++;
			if( tangent ) expected += 2;

			assertEquals(expected, param.numParameters());
		}
	}

	@Test
	public void setFromParam_convertToParam() {
		for (int i = 0; i < 4; i++) {
			boolean skew = i % 2 == 0;
			boolean tangent = i / 2 == 0;

			CalibParamPinholeRadial param = new CalibParamPinholeRadial(skew, 2, tangent);

			double d[] = new double[ param.numParameters()];
			for (int j = 0; j < d.length; j++) {
				d[j] = j+1;
			}

			param.setFromParam(d);

			int c = 1;
			assertEquals(c++, param.intrinsic.fx, 1e-8);
			assertEquals(c++, param.intrinsic.fy, 1e-8);
			if( !skew )
				assertEquals(c++, param.intrinsic.skew, 1e-8);
			else
				assertEquals(0, param.intrinsic.skew, 1e-8);
			assertEquals(c++, param.intrinsic.cx, 1e-8);
			assertEquals(c++, param.intrinsic.cy, 1e-8);
			assertEquals(c++, param.intrinsic.radial[0], 1e-8);
			assertEquals(c++, param.intrinsic.radial[1], 1e-8);
			if (tangent) {
				assertEquals(c++, param.intrinsic.t1, 1e-8);
				assertEquals(c, param.intrinsic.t2, 1e-8);
			} else {
				assertEquals(0, param.intrinsic.t1, 1e-8);
				assertEquals(0, param.intrinsic.t2, 1e-8);
			}

			double e[] = new double[d.length];
			param.convertToParam(e);

			for (int j = 0; j < e.length; j++) {
				assertTrue(e[j]==d[j]);
			}
		}
	}
}
