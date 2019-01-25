/**
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Copyright 2014 Edgar Espina
 */
package io.jooby;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;

public interface TemplateEngine extends Renderer {

  String apply(Context ctx, ModelAndView modelAndView) throws Exception;

  @Override default byte[] encode(@Nonnull Context ctx, @Nonnull Object value) throws Exception {
    ctx.defaultResponseType(MediaType.html);
    String output = apply(ctx, (ModelAndView) value);
    return output.getBytes(StandardCharsets.UTF_8);
  }
}